package com.mkpro.infra.network.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing discovered network peers.
 * Implemented as a Singleton for centralized access across the application.
 */
public class NetworkPeerRegistry {

    private static final NetworkPeerRegistry INSTANCE = new NetworkPeerRegistry();

    private final Map<String, PeerInfo> peers = new ConcurrentHashMap<>();

    private NetworkPeerRegistry() {
    }

    public static NetworkPeerRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Adds or updates a peer in the registry.
     * @param peer The peer information to register.
     */
    public void addPeer(PeerInfo peer) {
        if (peer != null && peer.getPeerId() != null) {
            peers.put(peer.getPeerId(), peer);
        }
    }

    /**
     * Removes a peer from the registry by its peer ID.
     * @param peerId The unique identifier of the peer.
     */
    public void removePeer(String peerId) {
        if (peerId != null) {
            peers.remove(peerId);
        }
    }

    /**
     * Returns a snapshot of all currently registered peers.
     * @return A list of PeerInfo objects.
     */
    public List<PeerInfo> listPeers() {
        return new ArrayList<>(peers.values());
    }

    /**
     * POJO representing a network peer.
     */
    public static class PeerInfo {
        private final String peerId;
        private final String ip;
        private final int port;

        public PeerInfo(String peerId, String ip, int port) {
            this.peerId = peerId;
            this.ip = ip;
            this.port = port;
        }

        public String getPeerId() { return peerId; }
        public String getIp() { return ip; }
        public int getPort() { return port; }

        // Aliases for backward compatibility and SwingCompanion expectations
        public String getInstanceId() { return peerId; }
        public String getHost() { return ip; }

        @Override
        public String toString() {
            return String.format("PeerInfo{id='%s', address=%s:%d}", peerId, ip, port);
        }
    }
}
