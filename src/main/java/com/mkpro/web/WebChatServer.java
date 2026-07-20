package com.mkpro.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedded web server for mkpro chat UI.
 * 
 * Serves:
 * - HTTP on port (default 8080): static index.html
 * - WebSocket on port+1 (default 8081): real-time chat events
 *
 * The web client connects to the WebSocket and sends/receives JSON messages:
 *   Client → Server: {"type": "user_input", "text": "..."}
 *   Server → Client: {"type": "stream_start", "agent": "Coordinator"}
 *   Server → Client: {"type": "stream_chunk", "text": "..."}
 *   Server → Client: {"type": "stream_end"}
 *   Server → Client: {"type": "maker", "message": "..."}
 *   Server → Client: {"type": "routing", "message": "..."}
 *   Server → Client: {"type": "delegation", "agent": "SysAdmin"}
 */
public class WebChatServer {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final int httpPort;
    private final int wsPort;
    private HttpServer httpServer;
    private ChatWebSocketServer wsServer;
    private volatile WebInputHandler inputHandler;
    private volatile com.mkpro.CentralMemory centralMemory;
    private volatile com.mkpro.knowledge.KnowledgeStore knowledgeStore;
    private volatile com.mkpro.knowledge.TopicIndex topicIndex;

    // All connected WebSocket clients
    private final Set<WebSocket> clients = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public WebChatServer(int httpPort) {
        this.httpPort = httpPort;
        this.wsPort = httpPort + 1;
    }

    /**
     * Set the CentralMemory reference for the /db browser.
     */
    public void setCentralMemory(com.mkpro.CentralMemory memory) {
        this.centralMemory = memory;
    }

    /**
     * Set knowledge components for the /knowledge page.
     */
    public void setKnowledgeComponents(com.mkpro.knowledge.KnowledgeStore store, com.mkpro.knowledge.TopicIndex index) {
        this.knowledgeStore = store;
        this.topicIndex = index;
    }

    /**
     * Set the handler that processes user input from web clients.
     */
    public void setInputHandler(WebInputHandler handler) {
        this.inputHandler = handler;
    }

    /**
     * Start both HTTP and WebSocket servers.
     */
    public void start() throws IOException {
        // HTTP server for static files
        httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
        httpServer.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path) || "/index.html".equals(path)) {
                serveResource(exchange, "/web/index.html", "text/html");
            } else if ("/db".equals(path)) {
                serveResource(exchange, "/web/db.html", "text/html");
            } else if ("/knowledge".equals(path)) {
                serveResource(exchange, "/web/knowledge.html", "text/html");
            } else if ("/api/db".equals(path)) {
                serveDbApi(exchange);
            } else if ("/api/knowledge".equals(path)) {
                serveKnowledgeApi(exchange);
            } else if (path.startsWith("/api/knowledge/search")) {
                serveKnowledgeSearchApi(exchange);
            } else if (path.startsWith("/api/files")) {
                serveFilesApi(exchange);
            } else if (path.startsWith("/api/file-content")) {
                serveFileContentApi(exchange);
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            }
        });
        httpServer.setExecutor(null);
        httpServer.start();

        // WebSocket server
        wsServer = new ChatWebSocketServer(new InetSocketAddress(wsPort));
        wsServer.start();

        System.out.println("\u001b[36m[Web UI] http://localhost:" + httpPort + " (WebSocket: ws://localhost:" + wsPort + ")\u001b[0m");
    }

    /**
     * Stop both servers.
     */
    public void stop() {
        if (httpServer != null) httpServer.stop(0);
        if (wsServer != null) {
            try { wsServer.stop(1000); } catch (Exception e) { /* ignore */ }
        }
    }

    // === Broadcasting to all web clients ===

    /**
     * Broadcast a stream start event (new response beginning).
     */
    public void broadcastStreamStart(String agent, String model) {
        broadcast(createMessage("stream_start")
            .put("agent", agent)
            .put("model", model != null ? model : ""));
    }

    /**
     * Broadcast a text chunk (token-by-token streaming).
     */
    public void broadcastStreamChunk(String text) {
        broadcast(createMessage("stream_chunk").put("text", text));
    }

    /**
     * Broadcast stream end.
     */
    public void broadcastStreamEnd() {
        broadcast(createMessage("stream_end"));
    }

    /**
     * Broadcast a Maker observation.
     */
    public void broadcastMaker(String message) {
        broadcast(createMessage("maker").put("message", message));
    }

    /**
     * Broadcast a routing decision.
     */
    public void broadcastRouting(String message) {
        broadcast(createMessage("routing").put("message", message));
    }

    /**
     * Broadcast a delegation event.
     */
    public void broadcastDelegation(String agent) {
        broadcast(createMessage("delegation").put("agent", agent));
    }

    public boolean hasClients() {
        return !clients.isEmpty();
    }

    // === Internal ===

    private ObjectNode createMessage(String type) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", type);
        return node;
    }

    private void broadcast(ObjectNode message) {
        if (clients.isEmpty()) return;
        String json = message.toString();
        for (WebSocket client : clients) {
            try {
                if (client.isOpen()) client.send(json);
            } catch (Exception e) { /* skip dead clients */ }
        }
    }

    private void handleWebInput(String json) {
        try {
            ObjectNode msg = (ObjectNode) mapper.readTree(json);
            String type = msg.has("type") ? msg.get("type").asText() : "";
            if ("user_input".equals(type)) {
                String text = msg.has("text") ? msg.get("text").asText().trim() : "";

                // Process file attachments — prepend content to the message
                if (msg.has("attachments") && msg.get("attachments").isArray()) {
                    StringBuilder contextBuilder = new StringBuilder();
                    for (com.fasterxml.jackson.databind.JsonNode attachment : msg.get("attachments")) {
                        String name = attachment.has("name") ? attachment.get("name").asText() : "file";
                        String content = attachment.has("content") ? attachment.get("content").asText() : "";
                        boolean isImage = attachment.has("isImage") && attachment.get("isImage").asBoolean();

                        if (isImage) {
                            // For images, include as a data URL reference (vision tool can process)
                            contextBuilder.append("[Attached image: ").append(name).append("]\n");
                            // Store image data URL — truncate display but keep reference
                            if (content.length() > 100) {
                                contextBuilder.append("[Image data: ").append(content.substring(0, 50)).append("...]\n\n");
                            }
                        } else {
                            // For text files, include content directly
                            contextBuilder.append("--- File: ").append(name).append(" ---\n");
                            // Cap at 10000 chars per file to prevent token overflow
                            if (content.length() > 10000) {
                                contextBuilder.append(content, 0, 10000);
                                contextBuilder.append("\n... [truncated, ").append(content.length()).append(" chars total]\n");
                            } else {
                                contextBuilder.append(content);
                            }
                            contextBuilder.append("\n--- End: ").append(name).append(" ---\n\n");
                        }
                    }

                    // Prepend file context to user message
                    if (contextBuilder.length() > 0) {
                        text = contextBuilder.toString() + (text.isEmpty() ? "Analyze the attached file(s)." : text);
                    }
                }

                if (!text.isEmpty() && inputHandler != null) {
                    inputHandler.onWebInput(text);
                }
            }
        } catch (Exception e) {
            // Ignore malformed messages
        }
    }

    private void serveKnowledgeApi(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (knowledgeStore == null) {
            byte[] err = "{\"topics\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, err.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
            return;
        }

        try {
            java.util.List<com.mkpro.knowledge.TopicReport> reports = knowledgeStore.getAllReports();
            java.util.Map<String, com.mkpro.knowledge.TopicReport> topicMap = new java.util.LinkedHashMap<>();
            for (com.mkpro.knowledge.TopicReport r : reports) {
                topicMap.put(r.getName(), r);
            }
            java.util.Map<String, Object> response = java.util.Map.of("topics", topicMap);
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(response);
            byte[] content = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(content); }
        } catch (Exception e) {
            byte[] err = ("{\"error\":\"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(500, err.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
        }
    }

    private void serveKnowledgeSearchApi(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (topicIndex == null) {
            byte[] err = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, err.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
            return;
        }

        try {
            // Parse query from ?q=...
            String query = "";
            String rawQuery = exchange.getRequestURI().getQuery();
            if (rawQuery != null) {
                for (String param : rawQuery.split("&")) {
                    if (param.startsWith("q=")) {
                        query = java.net.URLDecoder.decode(param.substring(2), StandardCharsets.UTF_8);
                    }
                }
            }

            if (query.isBlank()) {
                byte[] empty = "[]".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(200, empty.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(empty); }
                return;
            }

            java.util.List<com.mkpro.knowledge.TopicIndex.SearchResult> results = topicIndex.search(query, 10);
            String json = mapper.writeValueAsString(results);
            byte[] content = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(content); }
        } catch (Exception e) {
            byte[] err = ("{\"error\":\"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(500, err.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
        }
    }

    private static final java.util.Set<String> EXCLUDED_DIRS = java.util.Set.of(
        "target", ".git", "node_modules", ".mkpro", "build", "out", ".idea", ".vscode", ".gradle", ".cache"
    );

    private void serveFilesApi(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        try {
            // Parse ?path= parameter
            String relativePath = "";
            String rawQuery = exchange.getRequestURI().getQuery();
            if (rawQuery != null) {
                for (String param : rawQuery.split("&")) {
                    if (param.startsWith("path=")) {
                        relativePath = java.net.URLDecoder.decode(param.substring(5), StandardCharsets.UTF_8);
                    }
                }
            }

            // Security: resolve against project root, prevent traversal
            java.nio.file.Path projectRoot = java.nio.file.Paths.get("").toAbsolutePath();
            java.nio.file.Path targetDir = projectRoot.resolve(relativePath).normalize();
            if (!targetDir.startsWith(projectRoot)) {
                byte[] err = "{\"error\":\"Access denied\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(403, err.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
                return;
            }

            if (!java.nio.file.Files.isDirectory(targetDir)) {
                byte[] err = "{\"error\":\"Not a directory\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(404, err.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
                return;
            }

            // List directory entries
            java.util.List<java.util.Map<String, Object>> entries = new java.util.ArrayList<>();
            try (java.nio.file.DirectoryStream<java.nio.file.Path> stream = java.nio.file.Files.newDirectoryStream(targetDir)) {
                for (java.nio.file.Path entry : stream) {
                    String name = entry.getFileName().toString();
                    boolean isDir = java.nio.file.Files.isDirectory(entry);

                    // Skip excluded directories
                    if (isDir && EXCLUDED_DIRS.contains(name)) continue;
                    // Skip hidden files (starting with .)
                    if (name.startsWith(".") && !name.equals(".mkpro")) continue;

                    java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
                    item.put("name", name);
                    item.put("type", isDir ? "directory" : "file");
                    if (!isDir) {
                        try {
                            item.put("size", java.nio.file.Files.size(entry));
                        } catch (Exception e) {
                            item.put("size", 0);
                        }
                    }
                    entries.add(item);
                }
            }

            // Sort: directories first, then alphabetical
            entries.sort((a, b) -> {
                boolean aDir = "directory".equals(a.get("type"));
                boolean bDir = "directory".equals(b.get("type"));
                if (aDir != bDir) return aDir ? -1 : 1;
                return ((String) a.get("name")).compareToIgnoreCase((String) b.get("name"));
            });

            java.util.Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("path", relativePath.isEmpty() ? "." : relativePath);
            response.put("entries", entries);

            String json = mapper.writeValueAsString(response);
            byte[] content = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(content); }

        } catch (Exception e) {
            byte[] err = ("{\"error\":\"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(500, err.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
        }
    }

    private void serveFileContentApi(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        try {
            String relativePath = "";
            String rawQuery = exchange.getRequestURI().getQuery();
            if (rawQuery != null) {
                for (String param : rawQuery.split("&")) {
                    if (param.startsWith("path=")) {
                        relativePath = java.net.URLDecoder.decode(param.substring(5), StandardCharsets.UTF_8);
                    }
                }
            }

            if (relativePath.isEmpty()) {
                byte[] err = "{\"error\":\"path parameter required\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(400, err.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
                return;
            }

            // Security: resolve against project root
            java.nio.file.Path projectRoot = java.nio.file.Paths.get("").toAbsolutePath();
            java.nio.file.Path targetFile = projectRoot.resolve(relativePath).normalize();
            if (!targetFile.startsWith(projectRoot)) {
                byte[] err = "{\"error\":\"Access denied\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(403, err.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
                return;
            }

            if (!java.nio.file.Files.isRegularFile(targetFile)) {
                byte[] err = "{\"error\":\"File not found\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(404, err.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
                return;
            }

            // Cap at 10KB
            long fileSize = java.nio.file.Files.size(targetFile);
            String fileContent;
            if (fileSize > 10240) {
                byte[] bytes = new byte[10240];
                try (java.io.InputStream is = java.nio.file.Files.newInputStream(targetFile)) {
                    is.read(bytes);
                }
                fileContent = new String(bytes, StandardCharsets.UTF_8) + "\n... [truncated at 10KB, total " + fileSize + " bytes]";
            } else {
                fileContent = java.nio.file.Files.readString(targetFile, StandardCharsets.UTF_8);
            }

            java.util.Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("path", relativePath);
            response.put("name", targetFile.getFileName().toString());
            response.put("size", fileSize);
            response.put("content", fileContent);

            String json = mapper.writeValueAsString(response);
            byte[] content = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(content); }

        } catch (Exception e) {
            byte[] err = ("{\"error\":\"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(500, err.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
        }
    }

    private void serveDbApi(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (centralMemory == null) {
            byte[] err = "{\"error\":\"CentralMemory not available\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(503, err.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
            return;
        }

        try {
            java.util.Map<String, java.util.Map<String, String>> stores = centralMemory.dumpAllStores();
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(stores);
            byte[] content = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(content); }
        } catch (Exception e) {
            byte[] err = ("{\"error\":\"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(500, err.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
        }
    }

    private void serveResource(com.sun.net.httpserver.HttpExchange exchange, String resourcePath, String contentType) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] content = is.readAllBytes();
            // Replace WS_PORT placeholder with actual port
            if (contentType.contains("html")) {
                String html = new String(content, StandardCharsets.UTF_8);
                html = html.replace("{{WS_PORT}}", String.valueOf(wsPort));
                content = html.getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(content);
            }
        }
    }

    /**
     * Inner WebSocket server using Java-WebSocket library.
     */
    private class ChatWebSocketServer extends WebSocketServer {

        public ChatWebSocketServer(InetSocketAddress address) {
            super(address);
            setReuseAddr(true);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            clients.add(conn);
            // Send welcome message
            ObjectNode welcome = createMessage("system");
            welcome.put("text", "Connected to mkpro. Type a message to begin.");
            conn.send(welcome.toString());
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            clients.remove(conn);
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            handleWebInput(message);
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            if (conn != null) clients.remove(conn);
        }

        @Override
        public void onStart() {
            // WebSocket server started
        }
    }

    /**
     * Callback interface for handling user input from web clients.
     */
    public interface WebInputHandler {
        void onWebInput(String text);
    }
}
