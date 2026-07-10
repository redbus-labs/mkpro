package com.mkpro.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Single;
import com.mkpro.infra.network.messaging.P2PMessageBus;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AskPeerAgentTool enables cross-instance agent-to-agent communication.
 * An agent on this instance can ask the same (or different) agent on a connected
 * peer instance for help, analysis, or information.
 *
 * The remote agent executes with READ-ONLY tools against its own project context
 * and returns the result via the P2P mesh.
 *
 * Usage by agents:
 *   ask_peer_agent(agent="Architect", question="Review the coupling in UserService")
 *
 * Timeout: 60 seconds. Returns error if no peer responds.
 */
public class AskPeerAgentTool {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String ANSI_PURPLE = "\u001b[35m";
    private static final String ANSI_RESET = "\u001b[0m";
    private static final int TIMEOUT_SECONDS = 60;

    /**
     * Creates the ask_peer_agent tool.
     * 
     * @param messageBus The P2PMessageBus for sending requests
     * @param localInstanceId This instance's ID (included in requests)
     */
    public static BaseTool create(P2PMessageBus messageBus, String localInstanceId) {
        return new BaseTool(
            "ask_peer_agent",
            "Asks an agent on a connected peer instance for help. The remote agent runs against " +
            "its own project context with read-only access. Use this when you need a second opinion, " +
            "cross-project knowledge, or when the remote instance has a more capable model. " +
            "The remote agent can read files, search codebase, and access graph memory on its own machine."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                    .name(name())
                    .description(description())
                    .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(ImmutableMap.of(
                            "agent", Schema.builder()
                                .type("STRING")
                                .description("Which agent to ask on the remote instance (e.g., 'Architect', 'Coder', 'SecurityAuditor').")
                                .build(),
                            "question", Schema.builder()
                                .type("STRING")
                                .description("The question or task for the remote agent. Be specific about what you need.")
                                .build(),
                            "context", Schema.builder()
                                .type("STRING")
                                .description("Optional: additional context to send with the request (e.g., a code snippet or design description).")
                                .build(),
                            "peer", Schema.builder()
                                .type("STRING")
                                .description("Optional: target a specific peer by project name or instance ID (e.g., 'payment-service', 'gpu-box'). If not specified, broadcasts to all peers.")
                                .build()
                        ))
                        .required(ImmutableList.of("agent", "question"))
                        .build())
                    .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String targetAgent = (String) args.get("agent");
                    String question = (String) args.get("question");
                    String context = args.get("context") != null ? (String) args.get("context") : "";
                    String peerTarget = args.get("peer") != null ? (String) args.get("peer") : null;

                    // Check if we have any connected peers
                    if (messageBus.getConnectedPeerUris().isEmpty()) {
                        return Collections.singletonMap("error", (Object) 
                            "No peers connected. Use '/network' to check connectivity or '/network connect <ip:port>' to connect manually.");
                    }

                    // If targeting a specific peer, verify it exists
                    if (peerTarget != null && !peerTarget.isEmpty()) {
                        var registry = com.mkpro.infra.network.discovery.NetworkPeerRegistry.getInstance();
                        var peer = registry.findByProject(peerTarget);
                        if (peer == null) peer = registry.findByInstanceId(peerTarget);
                        if (peer == null) {
                            return Map.of(
                                "error", "Peer '" + peerTarget + "' not found. Available peers:",
                                "peers", registry.listPeers().stream()
                                    .map(p -> p.getPeerId() + " [" + p.getProjectName() + "]")
                                    .collect(java.util.stream.Collectors.toList())
                            );
                        }
                    }

                    System.out.println(ANSI_PURPLE + ">> Asking " + 
                        (peerTarget != null ? peerTarget + "'s " : "peer's ") + targetAgent + ": " + 
                        (question.length() > 60 ? question.substring(0, 60) + "..." : question) + ANSI_RESET);

                    // Build the request
                    String requestId = UUID.randomUUID().toString();
                    ObjectNode request = mapper.createObjectNode();
                    request.put("type", "AGENT_REQUEST");
                    request.put("request_id", requestId);
                    request.put("from_instance", localInstanceId);
                    request.put("target_agent", targetAgent);
                    request.put("question", question);
                    request.put("context", context);
                    if (peerTarget != null && !peerTarget.isEmpty()) {
                        request.put("target_peer", peerTarget);
                    }
                    request.put("timestamp", System.currentTimeMillis());

                    // Send and wait for response
                    ObjectNode response = messageBus.sendRequestAndWait(request, TIMEOUT_SECONDS);

                    if (response == null) {
                        return Map.of(
                            "error", "No peer responded within " + TIMEOUT_SECONDS + "s. The remote agent may be busy or no peer has '" + targetAgent + "' available.",
                            "suggestion", "Check '/network' for connected peers. Ensure the peer instance is running."
                        );
                    }

                    boolean success = response.has("success") && response.get("success").asBoolean();
                    String agentResponse = response.has("response") ? response.get("response").asText() : "";
                    String respondingInstance = response.has("from_instance") ? response.get("from_instance").asText() : "unknown";

                    System.out.println(ANSI_PURPLE + "<< Response from " + respondingInstance + "'s " + targetAgent + 
                        " (" + agentResponse.length() + " chars)" + ANSI_RESET);

                    if (success) {
                        return Map.of(
                            "response", agentResponse,
                            "from_instance", respondingInstance,
                            "agent", targetAgent
                        );
                    } else {
                        return Map.of(
                            "error", agentResponse,
                            "from_instance", respondingInstance
                        );
                    }
                });
            }
        };
    }

    /**
     * Creates the list_peers tool — lets agents check who's connected before calling ask_peer_agent.
     */
    public static BaseTool createListPeersTool() {
        return new BaseTool(
            "list_peers",
            "Lists all connected peer instances with their project name, type, description, " +
            "available agents, and model. Use this to discover who you can ask before calling ask_peer_agent."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                    .name(name())
                    .description(description())
                    .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(Collections.emptyMap())
                        .build())
                    .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    var peers = com.mkpro.infra.network.discovery.NetworkPeerRegistry.getInstance().listPeers();
                    
                    if (peers.isEmpty()) {
                        return Collections.singletonMap("result", (Object) 
                            "No peers connected. Use '/network connect <ip:port>' to connect to another mkpro instance.");
                    }

                    StringBuilder sb = new StringBuilder("Connected peers:\n");
                    for (var peer : peers) {
                        sb.append("\n• ").append(peer.getPeerId());
                        if (peer.getProjectName() != null) {
                            sb.append(" [").append(peer.getProjectName()).append("/").append(peer.getProjectType()).append("]");
                        }
                        if (peer.getModel() != null) {
                            sb.append(" model:").append(peer.getModel());
                        }
                        if (peer.getAvailableAgents() != null && !peer.getAvailableAgents().isEmpty()) {
                            sb.append("\n  Agents: ").append(String.join(", ", peer.getAvailableAgents()));
                        }
                        if (peer.getProjectDescription() != null && !peer.getProjectDescription().isEmpty()
                            && !"No description available. Use /remember to add one.".equals(peer.getProjectDescription())) {
                            sb.append("\n  About: ").append(peer.getProjectDescription());
                        }
                        sb.append("\n");
                    }
                    sb.append("\nTo ask a peer: ask_peer_agent(agent=\"AgentName\", peer=\"project-name\", question=\"...\")");
                    
                    return Collections.singletonMap("result", (Object) sb.toString());
                });
            }
        };
    }
}
