package com.mkpro.core;

import com.google.adk.memory.MapDBVectorStore;
import com.mkpro.ActionLogger;

import static com.mkpro.MkPro.*;

/**
 * Handles graceful shutdown of MkPro — saves state, exports training data,
 * and closes all resources. Extracted from BootstrapService for clarity.
 */
class ShutdownHandler {

    private final MkProContext context;

    ShutdownHandler(MkProContext context) {
        this.context = context;
    }

    /**
     * Executes the full shutdown sequence. Called from the JVM shutdown hook.
     */
    void execute() {
        System.out.println("\n" + ANSI_YELLOW + "Shutting down MkPro..." + ANSI_RESET);

        // Save command history
        if (context.getLineReader() != null) {
            try {
                context.getLineReader().getHistory().save();
            } catch (Exception e) { /* Silent */ }
        }

        // Auto-save Markov model with live learning from this session
        if (context.getMarkovRouter() != null) {
            try {
                java.nio.file.Path mkproDir = com.mkpro.utils.PathUtils.getProjectPath().resolve(".mkpro");
                java.nio.file.Path modelPath = mkproDir.resolve("markov_model.dat");
                context.getMarkovRouter().save(modelPath);
            } catch (Exception e) { /* Silent */ }
        }

        // Auto-export session logs as training data for next startup
        try {
            java.util.List<String> logs = ActionLogger.getLogs();
            if (logs.size() > 5) { // Only export if meaningful interaction happened
                java.nio.file.Path dataDir = com.mkpro.utils.PathUtils.getMkproDataDir().resolve("datajsonl");
                java.nio.file.Files.createDirectories(dataDir);
                String timestamp = java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                java.nio.file.Path exportFile = dataDir.resolve("session_auto_" + timestamp + ".jsonl");

                // Quick export: extract USER→Coordinator pairs from logs
                exportSessionLogs(logs, exportFile);
            }
        } catch (Exception e) { /* Silent */ }

        // Auto-export Maker goal sequences (agents, tools, success, turns) for completion training
        try {
            if (context.getMakerLoop() != null) {
                java.nio.file.Path dataDir = com.mkpro.utils.PathUtils.getMkproDataDir().resolve("datajsonl");
                java.nio.file.Files.createDirectories(dataDir);
                java.nio.file.Path seqFile = dataDir.resolve("maker_sequences.jsonl");
                exportMakerSequences(context.getMakerLoop(), seqFile);
            }
        } catch (Exception e) { /* Silent */ }

        if (context.getDiscoveryService() != null) {
            context.getDiscoveryService().stop();
        }

        if (context.getP2pMessageBus() != null) {
            try {
                context.getP2pMessageBus().stop();
            } catch (Exception e) {
                // Ignore
            }
        }

        ActionLogger.shutdown();

        // Stop knowledge scheduler
        if (context.getKnowledgeScheduler() != null) {
            try {
                context.getKnowledgeScheduler().stop();
            } catch (Throwable e) { /* Ignore */ }
        }

        // Stop FactEngine
        if (context.getFactEngine() != null) {
            try {
                context.getFactEngine().persistProjectFacts();
                context.getFactEngine().shutdown();
            } catch (Throwable e) { /* Ignore */ }
        }

        if (context.getSessionService() instanceof AutoCloseable) {
            try {
                ((AutoCloseable) context.getSessionService()).close();
            } catch (Throwable e) {
                // Ignore — classes may be unloaded during shutdown
            }
        }

        if (context.getArtifactService() instanceof AutoCloseable) {
            try {
                ((AutoCloseable) context.getArtifactService()).close();
            } catch (Throwable e) {
                // Ignore — classes may be unloaded during shutdown
            }
        }

        if (context.getVectorStore() instanceof MapDBVectorStore) {
            try {
                ((MapDBVectorStore) context.getVectorStore()).close();
            } catch (Throwable e) {
                // Ignore — MapDB's CleanerUtil may be unloaded during shutdown
            }
        }

        if (context.getCentralMemory() != null) {
            try {
                context.getCentralMemory().close();
            } catch (Throwable e) {
                // Ignore — MapDB may be partially unloaded during shutdown
            }
        }
    }

    /**
     * Quick export of session logs to JSONL for Markov training.
     * Extracts USER→agent response pairs from ActionLogger entries.
     */
    static void exportSessionLogs(java.util.List<String> logs, java.nio.file.Path outputFile) {
        try (java.io.BufferedWriter writer = java.nio.file.Files.newBufferedWriter(outputFile)) {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String lastUserMsg = null;

            for (String log : logs) {
                // Parse: [timestamp] ROLE: content
                int bracketEnd = log.indexOf(']');
                if (bracketEnd < 0) continue;
                String rest = log.substring(bracketEnd + 2); // Skip "] "
                int colonIdx = rest.indexOf(':');
                if (colonIdx < 0) continue;

                String role = rest.substring(0, colonIdx).trim();
                String content = rest.substring(colonIdx + 1).trim();

                if ("USER".equals(role)) {
                    lastUserMsg = content;
                } else if (lastUserMsg != null && !"INFO".equals(role) && !"SYSTEM".equals(role)) {
                    // This is an agent response to the last user message
                    com.fasterxml.jackson.databind.node.ObjectNode line = mapper.createObjectNode();
                    com.fasterxml.jackson.databind.node.ArrayNode messages = mapper.createArrayNode();

                    com.fasterxml.jackson.databind.node.ObjectNode user = mapper.createObjectNode();
                    user.put("role", "user");
                    user.put("content", lastUserMsg);
                    messages.add(user);

                    com.fasterxml.jackson.databind.node.ObjectNode assistant = mapper.createObjectNode();
                    assistant.put("role", "assistant");
                    assistant.put("content", content);
                    messages.add(assistant);

                    line.set("messages", messages);
                    writer.write(mapper.writeValueAsString(line));
                    writer.newLine();

                    lastUserMsg = null; // Consumed
                }
            }
        } catch (Exception e) {
            // Silent — don't let export failure block shutdown
        }
    }

    /**
     * Append Maker goal sequences to maker_sequences.jsonl for future training.
     * Format: {"category":"CODING","agents":["Architect","Coder"],"tools":["file_read","file_write"],"turns":3,"success":true,"knowledge":["k8s-hpa"]}
     */
    static void exportMakerSequences(com.mkpro.routing.MakerLoop makerLoop, java.nio.file.Path outputFile) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        int exported = 0;

        try (java.io.BufferedWriter writer = java.nio.file.Files.newBufferedWriter(outputFile,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {

            for (com.mkpro.routing.MakerState goal : makerLoop.getAllGoals()) {
                // Only export goals that actually had turns (not trivial questions)
                if (goal.getTurnCount() < 1) continue;

                com.fasterxml.jackson.databind.node.ObjectNode node = mapper.createObjectNode();
                node.put("category", goal.getCategory().name());

                com.fasterxml.jackson.databind.node.ArrayNode agents = mapper.createArrayNode();
                for (String agent : goal.getAgentSequence()) agents.add(agent);
                node.set("agents", agents);

                com.fasterxml.jackson.databind.node.ArrayNode tools = mapper.createArrayNode();
                for (String tool : goal.getToolSequence()) tools.add(tool);
                node.set("tools", tools);

                node.put("turns", goal.getTurnCount());
                node.put("success", goal.getPhase() == com.mkpro.routing.MakerState.GoalPhase.DONE);

                // Include knowledge acquisition data if any
                if (!goal.getAcquiredTopics().isEmpty()) {
                    com.fasterxml.jackson.databind.node.ArrayNode knowledge = mapper.createArrayNode();
                    for (String topic : goal.getAcquiredTopics()) knowledge.add(topic);
                    node.set("knowledge", knowledge);
                    node.put("knowledge_retries", goal.getKnowledgeRetries());
                    node.put("knowledge_retry_successes", goal.getKnowledgeRetrySuccesses());
                }

                writer.write(mapper.writeValueAsString(node));
                writer.newLine();
                exported++;
            }
        } catch (Exception e) {
            // Silent — don't block shutdown
        }

        if (exported > 0) {
            System.out.println("  Exported " + exported + " Maker sequence(s) to .mkpro/datajsonl/maker_sequences.jsonl");
        }
    }
}
