package com.mkpro.infra.network.messaging;

import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;

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
 */
public class P2PMessageBus extends WebSocketServer {

    private final Set<WebSocketClient> outboundPeers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private Consumer<JSONObject> messageHandler;

    public P2PMessageBus(int port) {
        super(new InetSocketAddress(port));
    }

    public void setMessageHandler(Consumer<JSONObject> handler) {
        this.messageHandler = handler;
    }

    @Override
    public void onStart() {
        System.out.println("P2P Message Bus server started on port: " + getPort());
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("P2P: New inbound connection established from " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("P2P: Inbound connection closed: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        handleIncomingPayload(message);
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
                    System.out.println("P2P: Successfully connected to outbound peer: " + peerUri);
                }

                @Override
                public void onMessage(String message) {
                    handleIncomingPayload(message);
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
            System.err.println("P2P: Failed to initiate connection to " + peerUri + ": " + e.getMessage());
        }
    }

    /**
     * Broadcasts a JSONObject to all connected peers.
     * @param message The message to broadcast.
     */
    public void broadcast(JSONObject message) {
        String payload = message.toString();
        
        // Send to all inbound connections (clients connected to this server)
        for (WebSocket conn : getConnections()) {
            if (conn.isOpen()) {
                conn.send(payload);
            }
        }
        
        // Send to all outbound connections (peers this instance connected to)
        for (WebSocketClient peer : outboundPeers) {
            if (peer.isOpen()) {
                peer.send(payload);
            }
        }
    }

    private void handleIncomingPayload(String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            onMessageReceived(json);
        } catch (Exception e) {
            System.err.println("P2P: Malformed JSON received: " + e.getMessage());
        }
    }

    /**
     * Protected method called when a message is received. 
     * Subclasses should override this to implement custom logic.
     * @param message The received JSONObject.
     */
    protected void onMessageReceived(JSONObject message) {
        if (messageHandler != null) {
            messageHandler.accept(message);
        }
    }
}
