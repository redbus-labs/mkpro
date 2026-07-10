package com.mkpro.infra.network.peer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.BaseLlm;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.mkpro.SessionHelper;
import com.mkpro.agents.AgentManager;
import com.mkpro.infra.network.messaging.P2PMessageBus;
import com.mkpro.models.AgentConfig;
import com.mkpro.models.AgentDefinition;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PeerAgentRequestHandler processes AGENT_REQUEST messages from remote peers.
 * It executes the requested agent locally and sends the response back.
 * 
 * Security constraints:
 * - Remote requests only get read-only tools (file_read, codebase_search, graph_memory)
 * - No shell, file_write, or destructive tools for remote callers
 * - Responses are signed via MessageAuthenticator (existing P2P auth)
 */
public class PeerAgentRequestHandler {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String ANSI_PURPLE = "\u001b[35m";
    private static final String ANSI_RESET = "\u001b[0m";

    private final P2PMessageBus messageBus;
    private final AgentManager agentManager;
    private final Map<String, AgentConfig> agentConfigs;
    private final String localInstanceId;

    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "PeerAgent-Worker");
        t.setDaemon(true);
        return t;
    });

    public PeerAgentRequestHandler(P2PMessageBus messageBus, AgentManager agentManager,
                                    Map<String, AgentConfig> agentConfigs, String localInstanceId) {
        this.messageBus = messageBus;
        this.agentManager = agentManager;
        this.agentConfigs = agentConfigs;
        this.localInstanceId = localInstanceId;
    }

    /**
     * Handles an incoming AGENT_REQUEST message.
     * Executes the requested agent asynchronously and broadcasts the response.
     */
    public void handleRequest(ObjectNode request) {
        String requestId = request.get("request_id").asText();
        String fromInstance = request.get("from_instance").asText();
        String targetAgent = request.get("target_agent").asText();
        String question = request.get("question").asText();
        String context = request.has("context") ? request.get("context").asText() : "";
        String targetPeer = request.has("target_peer") ? request.get("target_peer").asText() : null;

        // If request targets a specific peer, check if it's us
        if (targetPeer != null && !targetPeer.isEmpty()) {
            String projectName = java.nio.file.Paths.get("").toAbsolutePath().getFileName().toString();
            boolean isForUs = localInstanceId.toLowerCase().contains(targetPeer.toLowerCase()) ||
                              projectName.toLowerCase().contains(targetPeer.toLowerCase());
            if (!isForUs) {
                return; // Not for us — ignore silently
            }
        }

        System.out.println(ANSI_PURPLE + ">> Remote request from " + fromInstance + 
            " → " + targetAgent + ": " + truncate(question, 80) + ANSI_RESET);

        // Execute asynchronously to not block the message handler
        executor.submit(() -> {
            String response;
            boolean success;
            try {
                response = executeForPeer(targetAgent, question, context);
                success = true;
            } catch (Exception e) {
                response = "Error executing " + targetAgent + ": " + e.getMessage();
                success = false;
            }

            // Send response back
            ObjectNode responseMsg = mapper.createObjectNode();
            responseMsg.put("type", "AGENT_RESPONSE");
            responseMsg.put("request_id", requestId);
            responseMsg.put("from_instance", localInstanceId);
            responseMsg.put("agent", targetAgent);
            responseMsg.put("response", response);
            responseMsg.put("success", success);
            responseMsg.put("timestamp", System.currentTimeMillis());

            messageBus.broadcast(responseMsg);
            System.out.println(ANSI_PURPLE + "<< Sent response to " + fromInstance + 
                " (" + (success ? "success" : "failed") + ", " + response.length() + " chars)" + ANSI_RESET);
        });
    }

    /**
     * Executes a local agent with read-only tools for a remote peer's question.
     */
    private String executeForPeer(String agentName, String question, String peerContext) {
        // Find the agent definition
        AgentDefinition def = agentManager.getAgentDefinitions().get(agentName);
        if (def == null) {
            // Try case-insensitive
            for (Map.Entry<String, AgentDefinition> entry : agentManager.getAgentDefinitions().entrySet()) {
                if (entry.getKey().equalsIgnoreCase(agentName)) {
                    def = entry.getValue();
                    agentName = entry.getKey();
                    break;
                }
            }
        }
        if (def == null) {
            return "Agent '" + agentName + "' not available on this instance.";
        }

        // Resolve LLM for the agent
        AgentConfig config = agentConfigs.getOrDefault(agentName, 
            new AgentConfig(com.mkpro.models.Provider.OLLAMA, "llama3"));
        BaseLlm llm = agentManager.createLlmPublic(config);
        if (llm == null) {
            return "Could not initialize LLM for " + agentName;
        }

        // Build instruction with peer context
        String instruction = def.getInstruction() + 
            "\n\n[REMOTE REQUEST: You are answering a question from a peer instance. " +
            "Provide your analysis based on YOUR local project context.]\n";
        if (peerContext != null && !peerContext.isEmpty()) {
            instruction += "\nPeer's context: " + peerContext + "\n";
        }

        // Build agent with READ-ONLY tools only (security: no write/shell for remote callers)
        List<com.google.adk.tools.BaseTool> readOnlyTools = agentManager.getToolRegistry()
            .resolve(List.of("file_read", "codebase_search", "graph_memory", "fetch_url"));

        LlmAgent agent = LlmAgent.builder()
            .name(agentName)
            .instruction(instruction)
            .model(llm)
            .tools(readOnlyTools)
            .build();

        Runner runner = Runner.builder()
            .agent(agent)
            .appName("mkpro-peer-" + localInstanceId)
            .sessionService(new com.google.adk.sessions.InMemorySessionService())
            .artifactService(new com.google.adk.artifacts.InMemoryArtifactService())
            .build();

        Session session = runner.sessionService()
            .createSession(new com.google.adk.sessions.SessionKey("mkpro-peer", agentName, "peer-" + System.currentTimeMillis()), new java.util.HashMap<>())
            .blockingGet();

        Content content = Content.builder().role("user").parts(List.of(Part.fromText(question))).build();

        StringBuilder output = new StringBuilder();
        runner.runAsync(agentName, session.id(), content)
            .blockingForEach(event -> {
                event.content().ifPresent(c -> {
                    c.parts().orElse(java.util.Collections.emptyList())
                        .forEach(p -> p.text().ifPresent(output::append));
                });
            });

        return output.toString();
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
