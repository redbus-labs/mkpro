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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * P2PMessageBus manages peer-to-peer WebSocket communication.
 * It extends WebSocketServer to accept incoming connections and uses 
 * WebSocketClient to initiate outgoing connections.
 * 
 * All messages are signed with HMAC-SHA256 using a shared cluster secret.
 * Unsigned or incorrectly signed messages are rejected.
 */
public class P2PMessageBus extends WebSocketServer {

    private static final String SIGNATURE_FIELD = "_sig";
    private static final String PAYLOAD_FIELD = "_payload";
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Set<WebSocketClient> outboundPeers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private Consumer<ObjectNode> messageHandler;
    private final MessageAuthenticator authenticator;

    public P2PMessageBus(int port) {
        super(new InetSocketAddress(port));
        this.authenticator = MessageAuthenticator.getInstance();
    }

    public void setMessageHandler(Consumer<ObjectNode> handler) {
        this.messageHandler = handler;
    }

    @Override
    public void onStart() {
        String authStatus = authenticator.isEnabled() ? "ENABLED" : "DISABLED";
        System.out.println("P2P Message Bus started on port: " + getPort() + " [Auth: " + authStatus + "]");
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("P2P: New inbound connection from " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("P2P: Inbound connection closed: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (!verifyAndHandle(message)) {
            System.err.println("P2P: Rejected message from " + conn.getRemoteSocketAddress() + " (auth failed)");
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("P2P Server-side Error: " + ex.getMessage());
    }

    /**
     * Connects to a remote peer.
     * @param peerUri The URI of the peer.
     */
    public void connectToPeer(String peerUri) {
        try {
            WebSocketClient client = new WebSocketClient(new URI(peerUri)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    System.out.println("P2P: Connected to outbound peer: " + peerUri);
                }

                @Override
                public void onMessage(String message) {
                    if (!verifyAndHandle(message)) {
                        System.err.println("P2P: Rejected message from peer " + peerUri + " (auth failed)");
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    System.out.println("P2P: Connection to outbound peer closed: " + peerUri);
                    outboundPeers.remove(this);
                }

                @Override
                public void onError(Exception ex) {
                    System.err.println("P2P Client-side Error (" + peerUri + "): " + ex.getMessage());
                }
            };
            client.connect();
            outboundPeers.add(client);
        } catch (Exception e) {
            System.err.println("P2P: Failed to connect to " + peerUri + ": " + e.getMessage());
        }
    }

    /**
     * Broadcasts an ObjectNode to all connected peers.
     * The message is signed before transmission.
     * @param message The message to broadcast.
     */
    public void broadcast(ObjectNode message) {
        String signedPayload = signMessage(message);
        
        // Send to all inbound connections (clients connected to this server)
        for (WebSocket conn : getConnections()) {
            if (conn.isOpen()) {
                conn.send(signedPayload);
            }
        }
        
        // Send to all outbound connections (peers this instance connected to)
        for (WebSocketClient peer : outboundPeers) {
            if (peer.isOpen()) {
                peer.send(signedPayload);
            }
        }
    }

    /**
     * Signs a message JSON and wraps it in an envelope with a signature.
     */
    private String signMessage(ObjectNode message) {
        String payload = message.toString();
        String signature = authenticator.sign(payload);

        if (signature != null) {
            ObjectNode envelope = mapper.createObjectNode();
            envelope.put(PAYLOAD_FIELD, payload);
            envelope.put(SIGNATURE_FIELD, signature);
            return envelope.toString();
        }
        
        // Auth disabled — send raw message for backward compatibility
        return payload;
    }

    /**
     * Verifies an incoming message and dispatches it if valid.
     * @return true if message was accepted, false if rejected
     */
    private boolean verifyAndHandle(String rawMessage) {
        try {
            ObjectNode parsed = (ObjectNode) mapper.readTree(rawMessage);
            
            // Check if this is a signed envelope
            if (parsed.has(PAYLOAD_FIELD) && parsed.has(SIGNATURE_FIELD)) {
                String payload = parsed.get(PAYLOAD_FIELD).asText();
                String signature = parsed.get(SIGNATURE_FIELD).asText();

                if (!authenticator.verify(payload, signature)) {
                    return false; // Signature verification failed
                }

                // Extract the actual message from the envelope
                ObjectNode actualMessage = (ObjectNode) mapper.readTree(payload);
                onMessageReceived(actualMessage);
                return true;
            }

            // Unsigned message — only accept if auth is disabled
            if (!authenticator.isEnabled()) {
                onMessageReceived(parsed);
                return true;
            }

            // Auth is enabled but message is unsigned — reject
            System.err.println("P2P: Rejected unsigned message (authentication is enabled)");
            return false;

        } catch (Exception e) {
            System.err.println("P2P: Malformed message: " + e.getMessage());
            return false;
        }
    }

    /**
     * Called when a verified message is received.
     * @param message The received ObjectNode (verified).
     */
    protected void onMessageReceived(ObjectNode message) {
        if (messageHandler != null) {
            messageHandler.accept(message);
        }
    }
}
