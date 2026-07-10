package com.mkpro.infra.network.peer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mkpro.CentralMemory;
import com.mkpro.infra.network.discovery.NetworkPeerRegistry;
import com.mkpro.infra.network.messaging.P2PMessageBus;
import com.mkpro.tools.McpProjectScanner;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * PeerHandshake manages the PEER_HELLO protocol.
 * On connection, instances exchange:
 * - Instance ID
 * - Project name, type, and description
 * - Available agents
 * - Primary model being used
 */
public class PeerHandshake {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String ANSI_CYAN = "\u001b[36m";
    private static final String ANSI_RESET = "\u001b[0m";

    private final P2PMessageBus messageBus;
    private final String instanceId;
    private final String projectName;
    private final String projectType;
    private final String projectDescription;
    private final List<String> availableAgents;
    private final String primaryModel;

    public PeerHandshake(P2PMessageBus messageBus, String instanceId,
                          List<String> availableAgents, String primaryModel,
                          CentralMemory centralMemory) {
        this.messageBus = messageBus;
        this.instanceId = instanceId;
        this.availableAgents = availableAgents;
        this.primaryModel = primaryModel;

        // Auto-detect project
        McpProjectScanner.ProjectInfo project = McpProjectScanner.detectProject(Paths.get("").toAbsolutePath());
        this.projectName = project.root.getFileName().toString();
        this.projectType = project.type;

        // Get project description from CentralMemory (set via /remember or commit_to_memory)
        String projectPath = System.getProperty("user.dir");
        String memory = centralMemory != null ? centralMemory.getMemory(projectPath) : "";
        if (memory != null && !memory.isEmpty()) {
            // Extract first 200 chars as description summary
            this.projectDescription = memory.length() > 200 ? memory.substring(0, 200) + "..." : memory;
        } else {
            this.projectDescription = "No description available. Use /remember to add one.";
        }
    }

    /**
     * Broadcasts a PEER_HELLO to all connected peers.
     */
    public void sendHello() {
        messageBus.broadcast(buildHelloMessage());
    }

    /**
     * Processes an incoming PEER_HELLO message.
     */
    public static void handleHello(ObjectNode message) {
        String peerId = message.has("instance_id") ? message.get("instance_id").asText() : "unknown";
        String projectName = message.has("project_name") ? message.get("project_name").asText() : "unknown";
        String projectType = message.has("project_type") ? message.get("project_type").asText() : "unknown";
        String model = message.has("model") ? message.get("model").asText() : "unknown";
        String description = message.has("project_description") ? message.get("project_description").asText() : "";

        List<String> agents = new ArrayList<>();
        if (message.has("agents") && message.get("agents").isArray()) {
            for (var node : message.get("agents")) {
                agents.add(node.asText());
            }
        }

        NetworkPeerRegistry registry = NetworkPeerRegistry.getInstance();
        registry.updatePeerInfo(peerId, projectName, projectType, agents, model);

        // Store description in PeerInfo
        NetworkPeerRegistry.PeerInfo peer = registry.findByInstanceId(peerId);
        if (peer != null) {
            peer.setProjectDescription(description);
        }

        System.out.println(ANSI_CYAN + "  ✓ Peer hello: " + peerId +
            " [" + projectName + "/" + projectType + "] " +
            agents.size() + " agents, model: " + model + ANSI_RESET);
        if (!description.isEmpty() && !"No description available. Use /remember to add one.".equals(description)) {
            System.out.println(ANSI_CYAN + "    Project: " + (description.length() > 80 ? description.substring(0, 80) + "..." : description) + ANSI_RESET);
        }
    }

    /**
     * Builds the PEER_HELLO message.
     */
    public ObjectNode buildHelloMessage() {
        ObjectNode hello = mapper.createObjectNode();
        hello.put("type", "PEER_HELLO");
        hello.put("instance_id", instanceId);
        hello.put("project_name", projectName);
        hello.put("project_type", projectType);
        hello.put("project_description", projectDescription);
        hello.put("model", primaryModel);
        hello.put("timestamp", System.currentTimeMillis());

        ArrayNode agentsArray = mapper.createArrayNode();
        for (String agent : availableAgents) {
            agentsArray.add(agent);
        }
        hello.set("agents", agentsArray);
        return hello;
    }
}
