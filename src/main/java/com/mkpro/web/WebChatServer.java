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
    private volatile com.mkpro.core.MkProContext mkproContext;
    private volatile com.mkpro.commands.CommandRegistry commandRegistry;

    private volatile RestApiHandler restApiHandler;

    // All connected WebSocket clients
    private final Set<WebSocket> clients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // Map WebSocket connection → user identity (IP/hostname)
    private final java.util.concurrent.ConcurrentHashMap<WebSocket, String> clientIdentities = new java.util.concurrent.ConcurrentHashMap<>();
    // Last sender identity (for ActionLogger attribution)
    private volatile String lastWebSender = "unknown";

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
     * Set the MkProContext for REST API access (runner, agents, etc.).
     */
    public void setContext(com.mkpro.core.MkProContext context) {
        this.mkproContext = context;
    }

    /**
     * Set the CommandRegistry for /api/command endpoint.
     */
    public void setCommandRegistry(com.mkpro.commands.CommandRegistry registry) {
        this.commandRegistry = registry;
    }

    /**
     * Set the handler that processes user input from web clients.
     */
    public void setInputHandler(WebInputHandler handler) {
        this.inputHandler = handler;
    }

    /**
     * Get the identity (IP/hostname) of the last web user who sent a message.
     */
    public String getLastWebSender() {
        return lastWebSender;
    }

    /**
     * Get the HTTP port this server is running on.
     */
    public int getHttpPort() {
        return httpPort;
    }

    /**
     * Get the number of connected WebSocket clients.
     */
    public int getClientCount() {
        return clients.size();
    }

    /**
     * Start both HTTP and WebSocket servers.
     */
    public void start() throws IOException {
        // Initialize REST API handler with all dependencies
        restApiHandler = new RestApiHandler(mkproContext, knowledgeStore, topicIndex,
            commandRegistry, centralMemory, this);

        // HTTP server for static files
        httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
        httpServer.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path) || "/index.html".equals(path) || "/academic".equals(path) || "/academic.html".equals(path) || "/academic_view.html".equals(path)) {
                serveResource(exchange, "/web/academic_view.html", "text/html");
            } else if ("/classic".equals(path) || "/classic/".equals(path) || "/classic.html".equals(path)) {
                serveResource(exchange, "/web/index.html", "text/html");
            } else if ("/db".equals(path) || "/db.html".equals(path)) {
                serveResource(exchange, "/web/db.html", "text/html");
            } else if ("/knowledge".equals(path) || "/knowledge.html".equals(path)) {
                serveResource(exchange, "/web/knowledge.html", "text/html");
            } else if (restApiHandler.handle(exchange, path)) {
                // Handled by RestApiHandler
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
     * Broadcast a system message.
     */
    public void broadcastSystem(String text) {
        broadcast(createMessage("system").put("text", text));
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

    public void broadcast(ObjectNode message) {
        if (clients.isEmpty()) return;
        String json = message.toString();
        for (WebSocket client : clients) {
            try {
                if (client.isOpen()) client.send(json);
            } catch (Exception e) { /* skip dead clients */ }
        }
    }

    /**
     * Broadcast to all clients EXCEPT the one matching the given sender identity.
     */
    private void broadcastExcluding(ObjectNode message, String excludeSender) {
        if (clients.isEmpty()) return;
        String json = message.toString();
        for (WebSocket client : clients) {
            try {
                String clientId = clientIdentities.getOrDefault(client, "");
                if (!clientId.equals(excludeSender) && client.isOpen()) {
                    client.send(json);
                }
            } catch (Exception e) { /* skip dead clients */ }
        }
    }

    private void handleWebInput(String json) {
        try {
            ObjectNode msg = (ObjectNode) mapper.readTree(json);
            String type = msg.has("type") ? msg.get("type").asText() : "";
            String sender = msg.has("sender") ? msg.get("sender").asText() : "unknown";

            if ("user_input".equals(type) || "chat".equals(type)) {
                String text = msg.has("text") ? msg.get("text").asText().trim() : (msg.has("message") ? msg.get("message").asText().trim() : "");

                // Broadcast the user message to OTHER web clients (not the sender)
                ObjectNode userMsg = createMessage("user_message");
                userMsg.put("sender", sender);
                userMsg.put("text", text);
                broadcastExcluding(userMsg, sender);

                // Process file attachments — prepend content to the message
                java.util.List<ImageAttachment> imageAttachments = new java.util.ArrayList<>();
                if (msg.has("attachments") && msg.get("attachments").isArray()) {
                    StringBuilder contextBuilder = new StringBuilder();
                    for (com.fasterxml.jackson.databind.JsonNode attachment : msg.get("attachments")) {
                        String name = attachment.has("name") ? attachment.get("name").asText() : "file";
                        String content = attachment.has("content") ? attachment.get("content").asText() : "";
                        boolean isImage = attachment.has("isImage") && attachment.get("isImage").asBoolean();

                        if (isImage) {
                            // Extract base64 data from data URL (data:image/png;base64,...)
                            String mimeType = attachment.has("type") ? attachment.get("type").asText() : "image/png";
                            if (content.startsWith("data:")) {
                                int commaIdx = content.indexOf(',');
                                if (commaIdx > 0) {
                                    byte[] imageBytes = java.util.Base64.getDecoder().decode(content.substring(commaIdx + 1));
                                    imageAttachments.add(new ImageAttachment(name, mimeType, imageBytes));
                                }
                            }
                            contextBuilder.append("[Attached image: ").append(name).append("]\n");
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
                    // Store sender on context for logging
                    lastWebSender = sender;
                    if (!imageAttachments.isEmpty()) {
                        inputHandler.onWebInputWithImages(text, imageAttachments);
                    } else {
                        inputHandler.onWebInput(text);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore malformed messages
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

            // Capture client identity (IP address or hostname)
            String identity = "unknown";
            try {
                java.net.InetSocketAddress remoteAddr = conn.getRemoteSocketAddress();
                if (remoteAddr != null) {
                    java.net.InetAddress addr = remoteAddr.getAddress();
                    String hostname = addr.getHostName();
                    String ip = addr.getHostAddress();
                    // Prefer hostname if resolved, otherwise use IP
                    identity = (hostname != null && !hostname.equals(ip)) ? hostname : ip;
                }
            } catch (Exception e) {
                // Fallback
            }
            clientIdentities.put(conn, identity);

            // Send welcome message with identity
            ObjectNode welcome = createMessage("system");
            welcome.put("text", "Connected to mkpro as " + identity + ". Type a message to begin.");
            conn.send(welcome.toString());
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            clients.remove(conn);
            clientIdentities.remove(conn);
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            // Inject sender identity into the message JSON
            String identity = clientIdentities.getOrDefault(conn, "unknown");
            try {
                com.fasterxml.jackson.databind.node.ObjectNode msg = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(message);
                msg.put("sender", identity);

                // Handle branch switch rejection via WebSocket
                String type = msg.has("type") ? msg.get("type").asText() : "";
                if ("branch_reject".equals(type)) {
                    String switchId = msg.has("id") ? msg.get("id").asText() : "";
                    restApiHandler.handleBranchReject(switchId);
                    return;
                }

                // Handle alias update
                if ("set_alias".equals(type)) {
                    String alias = msg.has("alias") ? msg.get("alias").asText().trim() : "";
                    if (!alias.isEmpty()) {
                        clientIdentities.put(conn, alias);
                    }
                    return;
                }

                handleWebInput(msg.toString());
            } catch (Exception e) {
                handleWebInput(message);
            }
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
        default void onWebInputWithImages(String text, java.util.List<ImageAttachment> images) {
            onWebInput(text); // fallback to text-only
        }
    }

    public record ImageAttachment(String name, String mimeType, byte[] data) {}
}