package com.mkpro.infra.network.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing discovered network peers.
 * Stores rich peer information including project context and available agents.
 */
public class NetworkPeerRegistry {

    private static final NetworkPeerRegistry INSTANCE = new NetworkPeerRegistry();

    private final Map<String, PeerInfo> peers = new ConcurrentHashMap<>();

    private NetworkPeerRegistry() {}

    public static NetworkPeerRegistry getInstance() {
        return INSTANCE;
    }

    public void addPeer(PeerInfo peer) {
        if (peer != null && peer.getPeerId() != null) {
            peers.put(peer.getPeerId(), peer);
        }
    }

    public void removePeer(String peerId) {
        if (peerId != null) {
            peers.remove(peerId);
        }
    }

    /**
     * Update an existing peer with handshake info (project, agents, model).
     * If the peer doesn't exist yet, creates a new entry.
     */
    public void updatePeerInfo(String peerId, String projectName, String projectType, 
                                List<String> agents, String model) {
        PeerInfo existing = peers.get(peerId);
        if (existing != null) {
            existing.setProjectName(projectName);
            existing.setProjectType(projectType);
            existing.setAvailableAgents(agents);
            existing.setModel(model);
        } else {
            PeerInfo newPeer = new PeerInfo(peerId, "unknown", 0);
            newPeer.setProjectName(projectName);
            newPeer.setProjectType(projectType);
            newPeer.setAvailableAgents(agents);
            newPeer.setModel(model);
            peers.put(peerId, newPeer);
        }
    }

    /**
     * Find a peer by project name (case-insensitive partial match).
     */
    public PeerInfo findByProject(String projectName) {
        for (PeerInfo peer : peers.values()) {
            if (peer.getProjectName() != null && 
                peer.getProjectName().toLowerCase().contains(projectName.toLowerCase())) {
                return peer;
            }
        }
        return null;
    }

    /**
     * Find a peer by instance ID (exact or partial match).
     */
    public PeerInfo findByInstanceId(String instanceId) {
        PeerInfo exact = peers.get(instanceId);
        if (exact != null) return exact;
        
        for (PeerInfo peer : peers.values()) {
            if (peer.getPeerId().toLowerCase().contains(instanceId.toLowerCase())) {
                return peer;
            }
        }
        return null;
    }

    public List<PeerInfo> listPeers() {
        return new ArrayList<>(peers.values());
    }

    /**
     * Represents a network peer with connection info and project context.
     */
    public static class PeerInfo {
        private final String peerId;
        private final String ip;
        private final int port;
        private String projectName;
        private String projectType;
        private String projectDescription;
        private List<String> availableAgents;
        private String model;

        public PeerInfo(String peerId, String ip, int port) {
            this.peerId = peerId;
            this.ip = ip;
            this.port = port;
            this.availableAgents = new ArrayList<>();
        }

        public String getPeerId() { return peerId; }
        public String getIp() { return ip; }
        public int getPort() { return port; }

        public String getProjectName() { return projectName; }
        public void setProjectName(String projectName) { this.projectName = projectName; }

        public String getProjectType() { return projectType; }
        public void setProjectType(String projectType) { this.projectType = projectType; }

        public String getProjectDescription() { return projectDescription; }
        public void setProjectDescription(String desc) { this.projectDescription = desc; }

        public List<String> getAvailableAgents() { return availableAgents; }
        public void setAvailableAgents(List<String> agents) { this.availableAgents = agents != null ? agents : new ArrayList<>(); }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        // Aliases for backward compat
        public String getInstanceId() { return peerId; }
        public String getHost() { return ip; }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(peerId).append(" (").append(ip).append(":").append(port).append(")");
            if (projectName != null) sb.append(" [").append(projectName).append("/").append(projectType).append("]");
            return sb.toString();
        }
    }
}
