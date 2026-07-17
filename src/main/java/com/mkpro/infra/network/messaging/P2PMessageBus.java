package com.mkpro.infra.network.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mkpro.security.MessageAuthenticator;
import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * P2PMessageBus manages peer-to-peer WebSocket communication.
 * Features:
 * - Server: accepts inbound connections from peers
 * - Client: connects to discovered peers
 * - HMAC-SHA256 message signing/verification
 * - Automatic reconnection with exponential backoff
 * - Deduplication of connections (won't connect to same peer twice)
 */
public class P2PMessageBus extends WebSocketServer {

    private static final String SIGNATURE_FIELD = "_sig";
    private static final String PAYLOAD_FIELD = "_payload";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long INITIAL_BACKOFF_MS = 2000;

    private final Set<WebSocketClient> outboundPeers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> connectedPeerUris = ConcurrentHashMap.newKeySet();
    private final com.mkpro.infra.network.security.P2PAuditLog auditLog = com.mkpro.infra.network.security.P2PAuditLog.getInstance();
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "P2P-Reconnect");
        t.setDaemon(true);
        return t;
    });

    private Consumer<ObjectNode> messageHandler;
    private final MessageAuthenticator authenticator;
    private final Map<String, CompletableFuture<ObjectNode>> pendingRequests = new ConcurrentHashMap<>();
    private Runnable onConnectHook; // Called when any new connection opens (for sending PEER_HELLO)

    public P2PMessageBus(int port) {
        super(new InetSocketAddress(port));
        this.authenticator = MessageAuthenticator.getInstance();
    }

    public void setMessageHandler(Consumer<ObjectNode> handler) {
        this.messageHandler = handler;
    }

    /**
     * Sets a hook that fires whenever a new connection opens (inbound or outbound).
     * Used by PeerHandshake to send PEER_HELLO on each new connection.
     */
    public void setOnConnectHook(Runnable hook) {
        this.onConnectHook = hook;
    }

    @Override
    public void onStart() {
        String authStatus = authenticator.isEnabled() ? "ENABLED" : "DISABLED";
        System.out.println("P2P Message Bus started on port: " + getPort() + " [Auth: " + authStatus + "]");
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String source = conn.getRemoteSocketAddress() != null ? conn.getRemoteSocketAddress().toString() : "unknown";
        String sourceIp = source.contains("/") ? source.substring(source.indexOf('/') + 1).split(":")[0] : source;
        
        // Whitelist check
        if (!auditLog.isAllowed(sourceIp)) {
            auditLog.log(com.mkpro.infra.network.security.P2PAuditLog.EventType.WHITELIST_REJECTED, sourceIp, "Inbound connection rejected");
            conn.close(1008, "Not in whitelist");
            return;
        }
        
        auditLog.log(com.mkpro.infra.network.security.P2PAuditLog.EventType.CONNECT_INBOUND, sourceIp, "Connection accepted");
        if (onConnectHook != null) {
            onConnectHook.run();
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        // Silent on normal close
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        String source = conn.getRemoteSocketAddress() != null ? conn.getRemoteSocketAddress().toString() : "unknown";
        if (!verifyAndHandle(message, source)) {
            // Silent — audit log handles alerting
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("P2P Error: " + ex.getMessage());
    }

    /**
     * Connects to a remote peer with automatic reconnection on failure.
     * Skips if already connected to this URI.
     * @param peerUri The WebSocket URI of the peer (e.g., ws://10.0.0.1:9000).
     */
    public void connectToPeer(String peerUri) {
        if (connectedPeerUris.contains(peerUri)) {
            return; // Already connected or connecting
        }
        connectedPeerUris.add(peerUri);
        connectWithBackoff(peerUri, 0);
    }

    private void connectWithBackoff(String peerUri, int attempt) {
        if (attempt >= MAX_RECONNECT_ATTEMPTS) {
            connectedPeerUris.remove(peerUri);
            return;
        }

        try {
            WebSocketClient client = new WebSocketClient(new URI(peerUri)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    // Silent — peer hello will confirm connection
                    if (onConnectHook != null) {
                        onConnectHook.run();
                    }
                }

                @Override
                public void onMessage(String message) {
                    if (!verifyAndHandle(message)) {
                        // Silent on auth failure for noisy peers
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    outboundPeers.remove(this);

                    // Schedule reconnection with backoff
                    if (remote) {
                        long delay = INITIAL_BACKOFF_MS * (long) Math.pow(2, attempt);
                        reconnectExecutor.schedule(() -> connectWithBackoff(peerUri, attempt + 1), delay, TimeUnit.MILLISECONDS);
                    } else {
                        connectedPeerUris.remove(peerUri);
                    }
                }

                @Override
                public void onError(Exception ex) {
                    // Silent — reconnect will handle it
                }
            };
            client.connect();
            outboundPeers.add(client);
        } catch (Exception e) {
            System.err.println("P2P: Failed to connect to " + peerUri + ": " + e.getMessage());
            connectedPeerUris.remove(peerUri);
        }
    }

    /**
     * Disconnects from a specific peer.
     */
    public void disconnectPeer(String peerUri) {
        connectedPeerUris.remove(peerUri);
        outboundPeers.removeIf(client -> {
            if (client.getURI().toString().equals(peerUri)) {
                client.close();
                return true;
            }
            return false;
        });
    }

    /**
     * Returns the set of currently connected (or connecting) peer URIs.
     */
    public Set<String> getConnectedPeerUris() {
        return Collections.unmodifiableSet(connectedPeerUris);
    }

    /**
     * Broadcasts an ObjectNode to all connected peers.
     * The message is signed before transmission.
     */
    public void broadcast(ObjectNode message) {
        String signedPayload = signMessage(message);

        // Send to all inbound connections
        for (WebSocket conn : getConnections()) {
            if (conn.isOpen()) {
                conn.send(signedPayload);
            }
        }

        // Send to all outbound connections
        for (WebSocketClient peer : outboundPeers) {
            if (peer.isOpen()) {
                peer.send(signedPayload);
            }
        }
    }

    private String signMessage(ObjectNode message) {
        String payload = message.toString();
        String signature = authenticator.sign(payload);

        if (signature != null) {
            ObjectNode envelope = mapper.createObjectNode();
            envelope.put(PAYLOAD_FIELD, payload);
            envelope.put(SIGNATURE_FIELD, signature);
            return envelope.toString();
        }

        return payload;
    }

    private boolean verifyAndHandle(String rawMessage) {
        return verifyAndHandle(rawMessage, "unknown");
    }

    private boolean verifyAndHandle(String rawMessage, String source) {
        try {
            ObjectNode parsed = (ObjectNode) mapper.readTree(rawMessage);

            if (parsed.has(PAYLOAD_FIELD) && parsed.has(SIGNATURE_FIELD)) {
                String payload = parsed.get(PAYLOAD_FIELD).asText();
                String signature = parsed.get(SIGNATURE_FIELD).asText();

                if (!authenticator.verify(payload, signature)) {
                    auditLog.log(com.mkpro.infra.network.security.P2PAuditLog.EventType.AUTH_FAILURE, 
                        source, "Invalid HMAC signature");
                    return false;
                }

                auditLog.log(com.mkpro.infra.network.security.P2PAuditLog.EventType.AUTH_SUCCESS, source, "Message verified");
                ObjectNode actualMessage = (ObjectNode) mapper.readTree(payload);
                onMessageReceived(actualMessage);
                return true;
            }

            if (!authenticator.isEnabled()) {
                onMessageReceived(parsed);
                return true;
            }

            return false;

        } catch (Exception e) {
            return false;
        }
    }

    protected void onMessageReceived(ObjectNode message) {
        // Check if this is a response to a pending request
        if (message.has("type") && "AGENT_RESPONSE".equals(message.get("type").asText())) {
            String requestId = message.has("request_id") ? message.get("request_id").asText() : null;
            if (requestId != null) {
                CompletableFuture<ObjectNode> future = pendingRequests.remove(requestId);
                if (future != null) {
                    future.complete(message);
                    return; // Don't pass to general handler
                }
            }
        }

        if (messageHandler != null) {
            messageHandler.accept(message);
        }
    }

    /**
     * Sends a request to all peers and waits for a response (first responder wins).
     * 
     * @param request The request message (must contain request_id)
     * @param timeoutSeconds How long to wait for a response
     * @return The response ObjectNode, or null if timeout
     */
    public ObjectNode sendRequestAndWait(ObjectNode request, int timeoutSeconds) {
        String requestId = request.has("request_id") ? request.get("request_id").asText() : null;
        if (requestId == null) {
            throw new IllegalArgumentException("Request must contain request_id");
        }

        CompletableFuture<ObjectNode> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        // Broadcast the request to all peers
        broadcast(request);

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pendingRequests.remove(requestId);
            return null; // Timeout — no peer responded
        } catch (Exception e) {
            pendingRequests.remove(requestId);
            System.err.println("P2P: Request failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Stops the message bus and cleans up resources.
     */
    public void stop() throws InterruptedException {
        reconnectExecutor.shutdown();
        for (WebSocketClient peer : outboundPeers) {
            peer.close();
        }
        outboundPeers.clear();
        connectedPeerUris.clear();
        super.stop();
    }
}
