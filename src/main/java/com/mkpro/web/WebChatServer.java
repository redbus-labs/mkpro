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
            } else if ("/api/db".equals(path)) {
                serveDbApi(exchange);
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
            if ("user_input".equals(type) && msg.has("text")) {
                String text = msg.get("text").asText().trim();
                if (!text.isEmpty() && inputHandler != null) {
                    inputHandler.onWebInput(text);
                }
            }
        } catch (Exception e) {
            // Ignore malformed messages
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
