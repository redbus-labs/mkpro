package com.mkpro.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mkpro.security.PathValidator;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Handles all REST API routes for the mkpro web server.
 * Extracted from WebChatServer to separate concerns.
 */
class RestApiHandler {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final Set<String> EXCLUDED_DIRS = Set.of(
        "target", ".git", "node_modules", ".mkpro", "build", "out", ".idea", ".vscode", ".gradle", ".cache"
    );

    private final com.mkpro.core.MkProContext mkproContext;
    private final com.mkpro.knowledge.KnowledgeStore knowledgeStore;
    private final com.mkpro.knowledge.TopicIndex topicIndex;
    private final com.mkpro.commands.CommandRegistry commandRegistry;
    private final com.mkpro.CentralMemory centralMemory;
    private final WebChatServer webChatServer;

    RestApiHandler(com.mkpro.core.MkProContext mkproContext,
                   com.mkpro.knowledge.KnowledgeStore knowledgeStore,
                   com.mkpro.knowledge.TopicIndex topicIndex,
                   com.mkpro.commands.CommandRegistry commandRegistry,
                   com.mkpro.CentralMemory centralMemory,
                   WebChatServer webChatServer) {
        this.mkproContext = mkproContext;
        this.knowledgeStore = knowledgeStore;
        this.topicIndex = topicIndex;
        this.commandRegistry = commandRegistry;
        this.centralMemory = centralMemory;
        this.webChatServer = webChatServer;
    }

    /**
     * Handle an API route. Returns true if the path was handled, false otherwise.
     */
    boolean handle(HttpExchange exchange, String path) throws IOException {
        switch (path) {
            case "/api/db":
                serveDbApi(exchange); return true;
            case "/api/knowledge":
                serveKnowledgeApi(exchange); return true;
            case "/api/knowledge/topics":
                handleKnowledgeTopicsApi(exchange); return true;
            case "/api/chat":
                handleChatApi(exchange); return true;
            case "/api/chat/stream":
                handleChatStreamApi(exchange); return true;
            case "/api/command":
                handleCommandApi(exchange); return true;
            case "/api/status":
                handleStatusApi(exchange); return true;
            case "/api/agents":
                handleAgentsApi(exchange); return true;
            case "/api/edit/approve":
                handleEditApproveApi(exchange); return true;
            case "/api/edit/reject":
                handleEditRejectApi(exchange); return true;
            case "/api/edit/pending":
                handleEditPendingApi(exchange); return true;
            case "/api/git/branch":
                handleGitBranchApi(exchange); return true;
            case "/api/git/switch":
                handleGitSwitchApi(exchange); return true;
            case "/api/models":
                handleModelsApi(exchange); return true;
            case "/api/teams":
                handleTeamsApi(exchange); return true;
            case "/api/goals":
                handleGoalsApi(exchange); return true;
            case "/api/sessions/new":
                handleNewSessionApi(exchange); return true;
            case "/api/ssh/connect":
                handleSshConnectApi(exchange); return true;
            case "/api/ssh/disconnect":
                handleSshDisconnectApi(exchange); return true;
            case "/api/ssh/status":
                handleSshStatusApi(exchange); return true;
            default:
                // Prefix-based routes
                if (path.startsWith("/api/knowledge/search")) {
                    serveKnowledgeSearchApi(exchange); return true;
                } else if (path.startsWith("/api/files")) {
                    serveFilesApi(exchange); return true;
                } else if (path.equals("/api/file") || path.startsWith("/api/file-content")) {
                    serveFileContentApi(exchange); return true;
                } else if (path.startsWith("/api/file-raw")) {
                    serveFileRawApi(exchange); return true;
                } else if (path.startsWith("/api/history")) {
                    handleHistoryApi(exchange); return true;
                }
                return false;
        }
    }

    // ========================================================================
    // JSON response helpers
    // ========================================================================

    private void sendJsonResponse(HttpExchange exchange, int code, Object data) throws IOException {
        String json = mapper.writeValueAsString(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private void sendJsonError(HttpExchange exchange, int code, String message) throws IOException {
        sendJsonResponse(exchange, code, Map.of("error", message != null ? message : "Unknown error"));
    }

    // ========================================================================
    // Git helpers
    // ========================================================================

    private static String getGitBranch() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD").start();
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String branch = br.readLine();
                p.waitFor(3, TimeUnit.SECONDS);
                return branch != null ? branch.trim() : "unknown";
            }
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Parse a log entry like "[2026-07-22T12:00:00.123] USER: hello world"
     * into {role, text, timestamp}.
     */
    private Map<String, String> parseLogEntry(String entry) {
        if (entry == null || entry.length() < 5) return null;

        try {
            int closeBracket = entry.indexOf(']');
            if (closeBracket < 0) return null;

            String timestamp = entry.substring(1, closeBracket);
            String rest = entry.substring(closeBracket + 2);

            int colonIdx = rest.indexOf(':');
            if (colonIdx < 0) return null;

            String role = rest.substring(0, colonIdx).trim();
            String text = rest.substring(colonIdx + 1).trim();

            if (text.isEmpty()) return null;

            Map<String, String> msg = new LinkedHashMap<>();
            msg.put("role", role);
            msg.put("text", text);
            msg.put("timestamp", timestamp);
            return msg;
        } catch (Exception e) {
            return null;
        }
    }


    // ========================================================================
    // Chat API handlers
    // ========================================================================

    /**
     * POST /api/chat — Synchronous chat. Send message, get full response.
     */
    private void handleChatApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        if (mkproContext == null || mkproContext.getRunner() == null || mkproContext.getCurrentSession() == null) {
            sendJsonError(exchange, 503, "Runner not available");
            return;
        }

        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode req = mapper.readTree(body);
            String message = req.has("message") ? req.get("message").asText() : "";

            if (message.isBlank()) {
                sendJsonError(exchange, 400, "message field required");
                return;
            }

            // Process attachments
            if (req.has("attachments") && req.get("attachments").isArray()) {
                StringBuilder contextBuilder = new StringBuilder();
                for (JsonNode att : req.get("attachments")) {
                    String name = att.has("name") ? att.get("name").asText() : "file";
                    String content = att.has("content") ? att.get("content").asText() : "";
                    contextBuilder.append("--- File: ").append(name).append(" ---\n");
                    if (content.length() > 10000) {
                        contextBuilder.append(content, 0, 10000).append("\n... [truncated]\n");
                    } else {
                        contextBuilder.append(content);
                    }
                    contextBuilder.append("\n--- End: ").append(name).append(" ---\n\n");
                }
                message = contextBuilder + message;
            }

            long startTime = System.currentTimeMillis();

            com.google.genai.types.Content content = com.google.genai.types.Content.fromParts(
                new com.google.genai.types.Part[]{com.google.genai.types.Part.fromText(message)});

            StringBuilder responseText = new StringBuilder();
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> errorRef = new AtomicReference<>();

            mkproContext.getRunner().runAsync(mkproContext.getCurrentSession().sessionKey(), content)
                .blockingSubscribe(
                    event -> {
                        event.content().ifPresent(c -> {
                            c.parts().ifPresent(parts -> {
                                for (com.google.genai.types.Part part : parts) {
                                    part.text().ifPresent(responseText::append);
                                }
                            });
                        });
                    },
                    error -> { errorRef.set(error.getMessage()); latch.countDown(); },
                    latch::countDown
                );

            latch.await(120, TimeUnit.SECONDS);
            long duration = System.currentTimeMillis() - startTime;

            if (errorRef.get() != null) {
                sendJsonError(exchange, 500, errorRef.get());
                return;
            }

            String agent = com.mkpro.agents.AgentManager.lastDelegatedAgent;

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("agent", agent != null ? agent : "Coordinator");
            response.put("response", responseText.toString());
            response.put("duration_ms", duration);

            sendJsonResponse(exchange, 200, response);

        } catch (Exception e) {
            sendJsonError(exchange, 500, e.getMessage());
        }
    }

    /**
     * POST /api/chat/stream — SSE streaming chat.
     */
    private void handleChatStreamApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        if (mkproContext == null || mkproContext.getRunner() == null || mkproContext.getCurrentSession() == null) {
            sendJsonError(exchange, 503, "Runner not available");
            return;
        }

        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode req = mapper.readTree(body);
            String message = req.has("message") ? req.get("message").asText() : "";

            if (message.isBlank()) {
                sendJsonError(exchange, 400, "message field required");
                return;
            }

            // Set SSE headers
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, 0); // chunked

            OutputStream os = exchange.getResponseBody();

            com.google.genai.types.Content content = com.google.genai.types.Content.fromParts(
                new com.google.genai.types.Part[]{com.google.genai.types.Part.fromText(message)});

            AtomicBoolean first = new AtomicBoolean(true);

            mkproContext.getRunner().runAsync(mkproContext.getCurrentSession().sessionKey(), content)
                .blockingSubscribe(
                    event -> {
                        event.content().ifPresent(c -> {
                            c.parts().ifPresent(parts -> {
                                for (com.google.genai.types.Part part : parts) {
                                    part.text().ifPresent(text -> {
                                        try {
                                            if (first.compareAndSet(true, false)) {
                                                String agent = com.mkpro.agents.AgentManager.lastDelegatedAgent;
                                                String startEvent = "data: " + mapper.writeValueAsString(
                                                    Map.of("type", "stream_start", "agent", agent != null ? agent : "Coordinator")) + "\n\n";
                                                os.write(startEvent.getBytes(StandardCharsets.UTF_8));
                                                os.flush();
                                            }
                                            String chunkEvent = "data: " + mapper.writeValueAsString(
                                                Map.of("type", "chunk", "text", text)) + "\n\n";
                                            os.write(chunkEvent.getBytes(StandardCharsets.UTF_8));
                                            os.flush();
                                        } catch (IOException ignored) {}
                                    });
                                }
                            });
                        });
                    },
                    error -> {
                        try {
                            String errEvent = "data: " + mapper.writeValueAsString(
                                Map.of("type", "error", "message", error.getMessage())) + "\n\n";
                            os.write(errEvent.getBytes(StandardCharsets.UTF_8));
                            os.flush();
                            os.close();
                        } catch (IOException ignored) {}
                    },
                    () -> {
                        try {
                            String endEvent = "data: " + mapper.writeValueAsString(
                                Map.of("type", "stream_end")) + "\n\n";
                            os.write(endEvent.getBytes(StandardCharsets.UTF_8));
                            os.flush();
                            os.close();
                        } catch (IOException ignored) {}
                    }
                );

        } catch (Exception e) {
            sendJsonError(exchange, 500, e.getMessage());
        }
    }

    /**
     * POST /api/command — Execute a CLI command.
     */
    private void handleCommandApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        if (mkproContext == null) {
            sendJsonError(exchange, 503, "Context not available");
            return;
        }

        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode req = mapper.readTree(body);
            String command = req.has("command") ? req.get("command").asText().trim() : "";

            if (command.isBlank()) {
                sendJsonError(exchange, 400, "command field required");
                return;
            }

            // Capture stdout
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.io.PrintStream capture = new java.io.PrintStream(baos, true, StandardCharsets.UTF_8);
            java.io.PrintStream originalOut = System.out;
            System.setOut(capture);

            try {
                if (commandRegistry != null) {
                    commandRegistry.executeCommand(command, mkproContext);
                }
            } finally {
                System.setOut(originalOut);
            }

            String output = baos.toString(StandardCharsets.UTF_8);
            // Strip ANSI escape codes
            output = output.replaceAll("\u001B\\[[;\\d]*m", "");

            sendJsonResponse(exchange, 200, Map.of("output", output));

        } catch (Exception e) {
            sendJsonError(exchange, 500, e.getMessage());
        }
    }


    // ========================================================================
    // Status / Agents / History
    // ========================================================================

    /**
     * GET /api/status — System status overview.
     */
    private void handleStatusApi(HttpExchange exchange) throws IOException {
        if (mkproContext == null) {
            sendJsonError(exchange, 503, "Context not available");
            return;
        }

        try {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("version", "4.1.1");
            status.put("runner", mkproContext.getCurrentRunnerType().get() != null ?
                mkproContext.getCurrentRunnerType().get().name() : "UNKNOWN");
            status.put("scheduler_active", mkproContext.getKnowledgeScheduler() != null);
            status.put("instance_name", mkproContext.getInstanceName());

            status.put("git_branch", getGitBranch());

            if (mkproContext.getAgentManager() != null) {
                status.put("agent_count", mkproContext.getAgentManager().getAgentDefinitions().size());
            }

            if (mkproContext.getMarkovRouter() != null) {
                status.put("markov_observations", mkproContext.getMarkovRouter().getTotalObservations());
                status.put("markov_threshold", mkproContext.getMarkovRouter().getConfidenceThreshold());
            }

            sendJsonResponse(exchange, 200, status);

        } catch (Exception e) {
            sendJsonError(exchange, 500, e.getMessage());
        }
    }

    /**
     * GET /api/agents — List all agents with their configurations.
     */
    private void handleAgentsApi(HttpExchange exchange) throws IOException {
        if (mkproContext == null) {
            sendJsonError(exchange, 503, "Context not available");
            return;
        }

        try {
            List<Map<String, Object>> agents = new ArrayList<>();

            if (mkproContext.getAgentManager() != null) {
                for (var def : mkproContext.getAgentManager().getAgentDefinitions().values()) {
                    Map<String, Object> agent = new LinkedHashMap<>();
                    agent.put("name", def.getName());
                    agent.put("description", def.getDescription());
                    agent.put("tools", def.getTools());
                    agent.put("needs_context", def.isNeedsContext());
                    if (def.getRoutingKeywords() != null) {
                        agent.put("routing_keywords", def.getRoutingKeywords());
                    }

                    var config = mkproContext.getAgentConfigs().get(def.getName());
                    if (config != null) {
                        agent.put("provider", config.getProvider().name());
                        agent.put("model", config.getModelName());
                    }
                    agents.add(agent);
                }
            }

            sendJsonResponse(exchange, 200, Map.of("agents", agents));

        } catch (Exception e) {
            sendJsonError(exchange, 500, e.getMessage());
        }
    }

    /**
     * GET /api/history?offset=N&limit=M — paginated chat history from ActionLogger.
     */
    private void handleHistoryApi(HttpExchange exchange) throws IOException {
        try {
            int offset = 0;
            int limit = 20;
            String rawQuery = exchange.getRequestURI().getQuery();
            if (rawQuery != null) {
                for (String param : rawQuery.split("&")) {
                    if (param.startsWith("offset=")) {
                        try { offset = Integer.parseInt(param.substring(7)); } catch (NumberFormatException ignored) {}
                    } else if (param.startsWith("limit=")) {
                        try { limit = Integer.parseInt(param.substring(6)); } catch (NumberFormatException ignored) {}
                    }
                }
            }
            limit = Math.min(limit, 50);

            List<String> allLogs = com.mkpro.ActionLogger.getAllLogs();
            int total = allLogs.size();

            List<Map<String, String>> messages = new ArrayList<>();

            int startIdx = Math.max(0, total - offset - limit);
            int endIdx = Math.max(0, total - offset);

            for (int i = startIdx; i < endIdx; i++) {
                String entry = allLogs.get(i);
                Map<String, String> msg = parseLogEntry(entry);
                if (msg != null) {
                    messages.add(msg);
                }
            }

            boolean hasMore = startIdx > 0;

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("messages", messages);
            response.put("total", total);
            response.put("hasMore", hasMore);
            response.put("offset", offset);

            sendJsonResponse(exchange, 200, response);

        } catch (Exception e) {
            sendJsonError(exchange, 500, e.getMessage());
        }
    }

    // ========================================================================
    // Edit approval API
    // ========================================================================

    /**
     * POST /api/edit/approve — Approve a pending edit proposal.
     */
    private void handleEditApproveApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1); exchange.close(); return;
        }
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode req = mapper.readTree(body);
            String id = req.has("id") ? req.get("id").asText() : "";

            com.mkpro.events.EditApprovalService service = com.mkpro.events.EditApprovalService.INSTANCE;
            if (service == null || id.isBlank()) {
                sendJsonError(exchange, 400, "Invalid request");
                return;
            }

            boolean found = service.approve(id);
            if (found) {
                sendJsonResponse(exchange, 200, Map.of("status", "approved", "id", id));
            } else {
                sendJsonError(exchange, 404, "Proposal not found or already resolved: " + id);
            }
        } catch (Exception e) {
            sendJsonError(exchange, 500, e.getMessage());
        }
    }

    /**
     * POST /api/edit/reject — Reject a pending edit proposal.
     */
    private void handleEditRejectApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1); exchange.close(); return;
        }
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode req = mapper.readTree(body);
            String id = req.has("id") ? req.get("id").asText() : "";

            com.mkpro.events.EditApprovalService service = com.mkpro.events.EditApprovalService.INSTANCE;
            if (service == null || id.isBlank()) {
                sendJsonError(exchange, 400, "Invalid request");
                return;
            }

            boolean found = service.reject(id);
            if (found) {
                sendJsonResponse(exchange, 200, Map.of("status", "rejected", "id", id));
            } else {
                sendJsonError(exchange, 404, "Proposal not found or already resolved: " + id);
            }
        } catch (Exception e) {
            sendJsonError(exchange, 500, e.getMessage());
        }
    }

    /**
     * GET /api/edit/pending — List all pending edit proposals.
     */
    private void handleEditPendingApi(HttpExchange exchange) throws IOException {
        try {
            com.mkpro.events.EditApprovalService service = com.mkpro.events.EditApprovalService.INSTANCE;
            if (service == null) {
                sendJsonResponse(exchange, 200, Map.of("pending", List.of()));
                return;
            }

            List<Map<String, Object>> pending = new ArrayList<>();
            for (var entry : service.getPendingProposals().entrySet()) {
                com.mkpro.events.EditProposal p = entry.getValue();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", p.getId());
                item.put("path", p.getFilePath());
                item.put("isNewFile", p.isNewFile());
                item.put("createdAt", p.getCreatedAt());
                item.put("diffLineCount", p.getDiffLines().size());
                pending.add(item);
            }

            sendJsonResponse(exchange, 200, Map.of("pending", pending));
        } catch (Exception e) {
            sendJsonError(exchange, 500, e.getMessage());
        }
    }


    // ========================================================================
    // Git API
    // ========================================================================

    /**
     * GET /api/git/branch — Get current branch and list all local branches.
     */
    private void handleGitBranchApi(HttpExchange exchange) throws IOException {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("current", getGitBranch());

            List<String> localBranches = new ArrayList<>();
            try {
                Process p = new ProcessBuilder("git", "branch", "--list").start();
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String branch = line.trim().replaceFirst("^\\* ", "");
                        if (!branch.isEmpty()) localBranches.add(branch);
                    }
                }
                p.waitFor(5, TimeUnit.SECONDS);
            } catch (Exception e) { /* ignore */ }

            List<String> remoteBranches = new ArrayList<>();
            try {
                Process p = new ProcessBuilder("git", "branch", "-r").start();
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String branch = line.trim();
                        if (branch.contains("->")) continue;
                        if (!branch.isEmpty()) remoteBranches.add(branch);
                    }
                }
                p.waitFor(5, TimeUnit.SECONDS);
            } catch (Exception e) { /* ignore */ }

            result.put("local", localBranches);
            result.put("remote", remoteBranches);

            List<String> all = new ArrayList<>(localBranches);
            for (String remote : remoteBranches) {
                String shortName = remote.contains("/") ? remote.substring(remote.indexOf('/') + 1) : remote;
                if (!localBranches.contains(shortName)) {
                    all.add(remote);
                }
            }
            result.put("branches", all);

            sendJsonResponse(exchange, 200, result);
        } catch (Exception e) {
            sendJsonError(exchange, 500, e.getMessage());
        }
    }

    /**
     * POST /api/git/switch — Request to switch to a different branch.
     */
    private void handleGitSwitchApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1); exchange.close(); return;
        }

        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode req = mapper.readTree(body);
            String branch = req.has("branch") ? req.get("branch").asText().trim() : "";
            String requestedBy = req.has("sender") ? req.get("sender").asText() : "unknown";

            if (branch.isEmpty()) {
                sendJsonError(exchange, 400, "branch field required");
                return;
            }

            if (branch.contains("..") || branch.contains(";") || branch.contains("&") || branch.contains("|")) {
                sendJsonError(exchange, 400, "Invalid branch name");
                return;
            }

            if (webChatServer.getClientCount() <= 1) {
                executeBranchSwitch(branch, exchange);
                return;
            }

            String switchId = "switch-" + System.currentTimeMillis();
            ObjectNode confirmMsg = mapper.createObjectNode();
            confirmMsg.put("type", "branch_confirm");
            confirmMsg.put("id", switchId);
            confirmMsg.put("branch", branch);
            confirmMsg.put("requested_by", requestedBy);
            confirmMsg.put("timeout", 5);
            webChatServer.broadcast(confirmMsg);

            pendingBranchSwitch = new PendingSwitch(switchId, branch, exchange);

            new Thread(() -> {
                try {
                    Thread.sleep(5000);
                    PendingSwitch pending = pendingBranchSwitch;
                    if (pending != null && pending.id.equals(switchId) && !pending.resolved) {
                        pending.resolved = true;
                        executeBranchSwitch(pending.branch, pending.exchange);
                    }
                } catch (Exception ignored) {}
            }, "branch-switch-timer").start();

        } catch (Exception e) {
            sendJsonError(exchange, 500, e.getMessage());
        }
    }

    private volatile PendingSwitch pendingBranchSwitch;

    private static class PendingSwitch {
        final String id;
        final String branch;
        final HttpExchange exchange;
        volatile boolean resolved = false;

        PendingSwitch(String id, String branch, HttpExchange exchange) {
            this.id = id;
            this.branch = branch;
            this.exchange = exchange;
        }
    }

    /**
     * Handle branch switch rejection from any user.
     */
    void handleBranchReject(String switchId) {
        PendingSwitch pending = pendingBranchSwitch;
        if (pending != null && pending.id.equals(switchId) && !pending.resolved) {
            pending.resolved = true;
            try {
                sendJsonError(pending.exchange, 409, "Branch switch rejected by another user");
            } catch (Exception ignored) {}

            ObjectNode msg = mapper.createObjectNode();
            msg.put("type", "system");
            msg.put("text", "⚠️ Branch switch to '" + pending.branch + "' was rejected.");
            webChatServer.broadcast(msg);
            pendingBranchSwitch = null;
        }
    }

    private void executeBranchSwitch(String branch, HttpExchange exchange) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "checkout", branch);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output;
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                output = br.lines().collect(Collectors.joining("\n"));
            }
            boolean success = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;

            if (success) {
                String newBranch = getGitBranch();
                ObjectNode msg = mapper.createObjectNode();
                msg.put("type", "branch_switched");
                msg.put("branch", newBranch);
                webChatServer.broadcast(msg);
                sendJsonResponse(exchange, 200, Map.of("status", "switched", "branch", newBranch));
            } else {
                sendJsonError(exchange, 400, "Switch failed: " + output);
            }
            pendingBranchSwitch = null;
        } catch (Exception e) {
            try { sendJsonError(exchange, 500, e.getMessage()); } catch (Exception ignored) {}
        }
    }

    // ========================================================================
    // SSH / Sandbox API Handlers
    // ========================================================================

    /**
     * POST /api/ssh/connect — Connect to a remote SSH/Sandbox host.
     */
    private void handleSshConnectApi(HttpExchange exchange) throws IOException {
        System.out.println("[RestApiHandler] >>> Incoming request: " + exchange.getRequestMethod() + " " + exchange.getRequestURI());
        System.out.println("[RestApiHandler] Headers: " + exchange.getRequestHeaders().entrySet());

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("[RestApiHandler] Body: " + (body.length() > 500 ? body.substring(0, 500) + "..." : body));

            JsonNode req = mapper.readTree(body);
            String host = req.has("host") ? req.get("host").asText().trim() : "";
            int port = req.has("port") ? req.get("port").asInt(22) : 22;
            String username = req.has("username") ? req.get("username").asText().trim() : "";
            String authTypeStr = req.has("authType") ? req.get("authType").asText() : "PASSWORD";
            String sessionAlias = req.has("sessionAlias") ? req.get("sessionAlias").asText().trim() : (req.has("alias") ? req.get("alias").asText().trim() : "default");

            String password = req.has("password") ? req.get("password").asText() : null;
            String privateKeyPath = req.has("privateKeyPath") ? req.get("privateKeyPath").asText().trim() : null;
            String privateKeyContent = req.has("privateKeyContent") ? req.get("privateKeyContent").asText() : null;
            String passphrase = req.has("passphrase") ? req.get("passphrase").asText() : null;

            if (host.isEmpty() || username.isEmpty()) {
                Map<String, Object> errResponse = Map.of("error", "host and username are required");
                System.out.println("[RestApiHandler] <<< Response 400: " + mapper.writeValueAsString(errResponse));
                sendJsonResponse(exchange, 400, errResponse);
                return;
            }

            com.mkpro.infra.ssh.AuthType authType = com.mkpro.infra.ssh.AuthType.fromString(authTypeStr);
            com.mkpro.infra.ssh.SshSessionManager mgr = com.mkpro.infra.ssh.SshSessionManager.getInstance();
            var entry = mgr.connect(host, port, username, password, privateKeyPath, privateKeyContent, passphrase, authType, sessionAlias);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", "connected");
            resp.put("alias", entry.getAlias());
            resp.put("host", entry.getHost());
            resp.put("port", entry.getPort());
            resp.put("username", entry.getUsername());

            String respJson = mapper.writeValueAsString(resp);
            System.out.println("[RestApiHandler] <<< Response 200: " + respJson);
            sendJsonResponse(exchange, 200, resp);

        } catch (Exception e) {
            System.out.println("[RestApiHandler] <<< Response 500 (Exception): " + e.getMessage());
            sendJsonResponse(exchange, 500, Map.of("error", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    /**
     * POST /api/ssh/disconnect — Disconnect an active SSH session by alias.
     */
    private void handleSshDisconnectApi(HttpExchange exchange) throws IOException {
        System.out.println("[RestApiHandler] >>> Incoming request: " + exchange.getRequestMethod() + " " + exchange.getRequestURI());
        System.out.println("[RestApiHandler] Headers: " + exchange.getRequestHeaders().entrySet());

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("[RestApiHandler] Body: " + body);

            JsonNode req = mapper.readTree(body);
            String alias = req.has("alias") ? req.get("alias").asText().trim() : (req.has("sessionAlias") ? req.get("sessionAlias").asText().trim() : "default");

            com.mkpro.infra.ssh.SshSessionManager mgr = com.mkpro.infra.ssh.SshSessionManager.getInstance();
            boolean disconnected = mgr.disconnect(alias);

            Map<String, Object> resp = Map.of(
                "status", disconnected ? "disconnected" : "not_found",
                "alias", alias
            );
            String respJson = mapper.writeValueAsString(resp);
            System.out.println("[RestApiHandler] <<< Response 200: " + respJson);
            sendJsonResponse(exchange, 200, resp);
        } catch (Exception e) {
            System.out.println("[RestApiHandler] <<< Response 500 (Exception): " + e.getMessage());
            sendJsonResponse(exchange, 500, Map.of("error", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    /**
     * GET /api/ssh/status — Check active SSH sessions status.
     */
    private void handleSshStatusApi(HttpExchange exchange) throws IOException {
        System.out.println("[RestApiHandler] >>> Incoming request: " + exchange.getRequestMethod() + " " + exchange.getRequestURI());
        System.out.println("[RestApiHandler] Headers: " + exchange.getRequestHeaders().entrySet());

        try {
            com.mkpro.infra.ssh.SshSessionManager mgr = com.mkpro.infra.ssh.SshSessionManager.getInstance();
            var sessions = mgr.listSessions();

            List<Map<String, Object>> sessionList = new ArrayList<>();
            for (var s : sessions) {
                if (s.isConnected()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("alias", s.getAlias());
                    item.put("host", s.getHost());
                    item.put("port", s.getPort());
                    item.put("username", s.getUsername());
                    item.put("connectedAt", s.getConnectedAt().toString());
                    sessionList.add(item);
                }
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("connected", !sessionList.isEmpty());
            resp.put("sessionCount", sessionList.size());
            resp.put("sessions", sessionList);

            String respJson = mapper.writeValueAsString(resp);
            System.out.println("[RestApiHandler] <<< Response 200: " + respJson);
            sendJsonResponse(exchange, 200, resp);
        } catch (Exception e) {
            System.out.println("[RestApiHandler] <<< Response 500 (Exception): " + e.getMessage());
            sendJsonResponse(exchange, 500, Map.of("error", e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    // ========================================================================
    // New API Handlers
    // ========================================================================

    private void handleModelsApi(HttpExchange exchange) throws IOException {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("models", com.mkpro.config.ModelRegistry.getAllModels());
        List<String> providers = Arrays.stream(com.mkpro.models.Provider.values())
            .map(Enum::name)
            .collect(Collectors.toList());
        response.put("providers", providers);
        sendJsonResponse(exchange, 200, response);
    }

    private void handleTeamsApi(HttpExchange exchange) throws IOException {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("teams", List.of("full", "coding", "minimal", "sysadmin", "research", "data_science"));
        response.put("active", "full");
        sendJsonResponse(exchange, 200, response);
    }

    private void handleGoalsApi(HttpExchange exchange) throws IOException {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("goals", centralMemory != null ? centralMemory.getGoals(".") : List.of());
        sendJsonResponse(exchange, 200, response);
    }

    private void handleNewSessionApi(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1); return;
        }
        if (commandRegistry != null && mkproContext != null) {
            commandRegistry.executeCommand("/new", mkproContext);
        }
        sendJsonResponse(exchange, 200, Map.of("status", "session_reset"));
    }

    // ========================================================================
    // Knowledge Topics API
    // ========================================================================

    /**
     * POST /api/knowledge/topics — Add a new topic.
     * DELETE /api/knowledge/topics?name= — Remove a topic.
     */
    private void handleKnowledgeTopicsApi(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();

        if ("POST".equals(method)) {
            if (mkproContext == null || mkproContext.getKnowledgeScheduler() == null) {
                sendJsonError(exchange, 503, "Knowledge scheduler not active. Start with --scheduler flag.");
                return;
            }

            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonNode req = mapper.readTree(body);

                String name = req.has("name") ? req.get("name").asText().trim() : "";
                if (name.isEmpty()) { sendJsonError(exchange, 400, "name field required"); return; }

                com.mkpro.knowledge.TopicConfig topic = new com.mkpro.knowledge.TopicConfig();
                topic.setName(name);
                topic.setTitle(req.has("title") ? req.get("title").asText() : name);
                topic.setInstruction(req.has("instruction") ? req.get("instruction").asText() : "");
                topic.setRefreshIntervalMinutes(req.has("refreshIntervalMinutes") ? req.get("refreshIntervalMinutes").asInt() : 60);
                if (req.has("agent")) topic.setAgent(req.get("agent").asText());

                List<String> sources = new ArrayList<>();
                if (req.has("sources") && req.get("sources").isArray()) {
                    for (JsonNode s : req.get("sources")) {
                        String url = s.asText().trim();
                        if (!url.isEmpty()) sources.add(url);
                    }
                }
                topic.setSources(sources);

                boolean created = mkproContext.getKnowledgeScheduler().addTopic(topic);
                if (created) {
                    sendJsonResponse(exchange, 201, Map.of(
                        "status", "created", "name", topic.getName(), "nextRefresh", "in 30s"));
                } else {
                    sendJsonError(exchange, 409, "Topic '" + name + "' already exists.");
                }
            } catch (Exception e) {
                sendJsonError(exchange, 500, e.getMessage());
            }

        } else if ("DELETE".equals(method)) {
            if (mkproContext == null || mkproContext.getKnowledgeScheduler() == null) {
                sendJsonError(exchange, 503, "Knowledge scheduler not active.");
                return;
            }

            String name = "";
            String rawQuery = exchange.getRequestURI().getQuery();
            if (rawQuery != null) {
                for (String param : rawQuery.split("&")) {
                    if (param.startsWith("name=")) {
                        name = URLDecoder.decode(param.substring(5), StandardCharsets.UTF_8);
                    }
                }
            }

            if (name.isEmpty()) { sendJsonError(exchange, 400, "name parameter required"); return; }

            boolean removed = mkproContext.getKnowledgeScheduler().removeTopic(name);
            if (removed) {
                sendJsonResponse(exchange, 200, Map.of("status", "deleted", "name", name));
            } else {
                sendJsonError(exchange, 404, "Topic '" + name + "' not found.");
            }

        } else {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
        }
    }


    // ========================================================================
    // Knowledge / DB / Files API
    // ========================================================================

    private void serveKnowledgeApi(HttpExchange exchange) throws IOException {
        if (knowledgeStore == null) {
            byte[] err = "{\"topics\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, err.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
            return;
        }

        try {
            List<com.mkpro.knowledge.TopicReport> reports = knowledgeStore.getAllReports();
            Map<String, com.mkpro.knowledge.TopicReport> topicMap = new LinkedHashMap<>();
            for (com.mkpro.knowledge.TopicReport r : reports) {
                topicMap.put(r.getName(), r);
            }
            Map<String, Object> response = Map.of("topics", topicMap);
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(response);
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

    private void serveKnowledgeSearchApi(HttpExchange exchange) throws IOException {
        if (topicIndex == null) {
            byte[] err = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, err.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
            return;
        }

        try {
            String query = "";
            String rawQuery = exchange.getRequestURI().getQuery();
            if (rawQuery != null) {
                for (String param : rawQuery.split("&")) {
                    if (param.startsWith("q=")) {
                        query = URLDecoder.decode(param.substring(2), StandardCharsets.UTF_8);
                    }
                }
            }

            if (query.isBlank()) {
                byte[] empty = "[]".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(200, empty.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(empty); }
                return;
            }

            List<com.mkpro.knowledge.TopicIndex.SearchResult> results = topicIndex.search(query, 10);
            String json = mapper.writeValueAsString(results);
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

    private void serveDbApi(HttpExchange exchange) throws IOException {
        if (centralMemory == null) {
            byte[] err = "{\"error\":\"CentralMemory not available\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(503, err.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
            return;
        }

        try {
            Map<String, Map<String, String>> stores = centralMemory.dumpAllStores();
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

    private void serveFilesApi(HttpExchange exchange) throws IOException {
        try {
            String relativePath = "";
            String rawQuery = exchange.getRequestURI().getQuery();
            if (rawQuery != null) {
                for (String param : rawQuery.split("&")) {
                    if (param.startsWith("path=")) {
                        relativePath = URLDecoder.decode(param.substring(5), StandardCharsets.UTF_8);
                    }
                }
            }

            Path targetDir;
            try {
                targetDir = PathValidator.getInstance().validateForRead(relativePath.isEmpty() ? "." : relativePath);
            } catch (SecurityException se) {
                sendJsonError(exchange, 403, "Access denied: " + se.getMessage());
                return;
            }

            if (!Files.isDirectory(targetDir)) {
                sendJsonError(exchange, 404, "Not a directory: " + relativePath);
                return;
            }

            List<Map<String, Object>> entries = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(targetDir)) {
                for (Path entry : stream) {
                    String name = entry.getFileName().toString();
                    boolean isDir = Files.isDirectory(entry);

                    if (isDir && EXCLUDED_DIRS.contains(name)) continue;
                    if (name.startsWith(".") && !name.equals(".mkpro")) continue;

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", name);
                    item.put("type", isDir ? "directory" : "file");
                    if (!isDir) {
                        try {
                            item.put("size", Files.size(entry));
                        } catch (Exception e) {
                            item.put("size", 0);
                        }
                    }
                    entries.add(item);
                }
            }

            entries.sort((a, b) -> {
                boolean aDir = "directory".equals(a.get("type"));
                boolean bDir = "directory".equals(b.get("type"));
                if (aDir != bDir) return aDir ? -1 : 1;
                return ((String) a.get("name")).compareToIgnoreCase((String) b.get("name"));
            });

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("path", relativePath.isEmpty() ? "." : relativePath);
            response.put("entries", entries);

            sendJsonResponse(exchange, 200, response);

        } catch (Exception e) {
            sendJsonError(exchange, 500, e.getMessage());
        }
    }

    private void serveFileContentApi(HttpExchange exchange) throws IOException {
        try {
            String relativePath = "";
            String rawQuery = exchange.getRequestURI().getQuery();
            if (rawQuery != null) {
                for (String param : rawQuery.split("&")) {
                    if (param.startsWith("path=")) {
                        relativePath = URLDecoder.decode(param.substring(5), StandardCharsets.UTF_8);
                    }
                }
            }

            if (relativePath.isEmpty()) {
                sendJsonError(exchange, 400, "path parameter required");
                return;
            }

            Path targetFile;
            try {
                targetFile = PathValidator.getInstance().validateForRead(relativePath);
            } catch (SecurityException se) {
                sendJsonError(exchange, 403, "Access denied: " + se.getMessage());
                return;
            }

            if (!Files.isRegularFile(targetFile)) {
                sendJsonError(exchange, 404, "File not found: " + relativePath);
                return;
            }

            long fileSize = Files.size(targetFile);
            String filename = targetFile.getFileName().toString();
            String mime = getMimeType(targetFile);
            boolean isBinary = isBinaryFile(targetFile, mime);

            String fileContent;
            if (isBinary) {
                fileContent = "[Binary file: " + mime + ", " + fileSize + " bytes. Use /api/file-raw?path=" + URLEncoder.encode(relativePath, StandardCharsets.UTF_8) + " to view or download]";
            } else if (fileSize > 51200) {
                byte[] bytes = new byte[51200];
                try (InputStream is = Files.newInputStream(targetFile)) {
                    int read = is.read(bytes);
                    fileContent = new String(bytes, 0, Math.max(0, read), StandardCharsets.UTF_8) + "\n... [truncated at 50KB, total " + fileSize + " bytes]";
                }
            } else {
                fileContent = Files.readString(targetFile, StandardCharsets.UTF_8);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("path", relativePath);
            response.put("name", filename);
            response.put("size", fileSize);
            response.put("mimeType", mime);
            response.put("isBinary", isBinary);
            response.put("rawUrl", "/api/file-raw?path=" + URLEncoder.encode(relativePath, StandardCharsets.UTF_8));
            response.put("content", fileContent);

            sendJsonResponse(exchange, 200, response);

        } catch (Exception e) {
            sendJsonError(exchange, 500, e.getMessage());
        }
    }

    private void serveFileRawApi(HttpExchange exchange) throws IOException {
        try {
            String relativePath = "";
            String rawQuery = exchange.getRequestURI().getQuery();
            if (rawQuery != null) {
                for (String param : rawQuery.split("&")) {
                    if (param.startsWith("path=")) {
                        relativePath = URLDecoder.decode(param.substring(5), StandardCharsets.UTF_8);
                    }
                }
            }

            if (relativePath.isEmpty()) {
                sendJsonError(exchange, 400, "path parameter required");
                return;
            }

            Path targetFile;
            try {
                targetFile = PathValidator.getInstance().validateForRead(relativePath);
            } catch (SecurityException se) {
                sendJsonError(exchange, 403, "Access denied: " + se.getMessage());
                return;
            }

            if (!Files.isRegularFile(targetFile)) {
                sendJsonError(exchange, 404, "File not found: " + relativePath);
                return;
            }

            long size = Files.size(targetFile);
            if (size > 100 * 1024 * 1024) {
                sendJsonError(exchange, 413, "File too large (>100MB)");
                return;
            }

            String mime = getMimeType(targetFile);
            String filename = targetFile.getFileName().toString();
            String safeFilename = filename.replaceAll("[\"\\r\\n]", "_");

            exchange.getResponseHeaders().set("Content-Type", mime);
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            exchange.getResponseHeaders().set("Content-Disposition", "inline; filename=\"" + safeFilename + "\"");

            // Stream file efficiently without buffering whole file in RAM
            exchange.sendResponseHeaders(200, size);
            try (InputStream is = Files.newInputStream(targetFile);
                 OutputStream os = exchange.getResponseBody()) {
                is.transferTo(os);
                os.flush();
            }

        } catch (Exception e) {
            try {
                sendJsonError(exchange, 500, e.getMessage());
            } catch (Exception ignored) {}
        }
    }

    static String getMimeType(Path file) {
        if (file == null || file.getFileName() == null) {
            return "application/octet-stream";
        }
        return getMimeType(file.getFileName().toString());
    }

    static String getMimeType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();

        // Documents & Formats
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".ppt")) return "application/vnd.ms-powerpoint";

        // Images
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".tiff") || lower.endsWith(".tif")) return "image/tiff";
        if (lower.endsWith(".avif")) return "image/avif";

        // 3D & Models
        if (lower.endsWith(".stl")) return "model/stl";
        if (lower.endsWith(".obj")) return "text/plain";
        if (lower.endsWith(".gltf")) return "model/gltf+json";
        if (lower.endsWith(".glb")) return "model/gltf-binary";
        if (lower.endsWith(".dxf")) return "text/plain";
        if (lower.endsWith(".step") || lower.endsWith(".stp")) return "application/step";

        // Web & Data
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/markdown";
        if (lower.endsWith(".txt") || lower.endsWith(".log")) return "text/plain";

        // Source Code & Config (Text)
        if (lower.endsWith(".java") || lower.endsWith(".py") || lower.endsWith(".c") ||
            lower.endsWith(".cpp") || lower.endsWith(".h") || lower.endsWith(".hpp") ||
            lower.endsWith(".rs") || lower.endsWith(".go") || lower.endsWith(".kt") ||
            lower.endsWith(".swift") || lower.endsWith(".sh") || lower.endsWith(".bat") ||
            lower.endsWith(".ps1") || lower.endsWith(".groovy") || lower.endsWith(".yaml") ||
            lower.endsWith(".yml") || lower.endsWith(".toml") || lower.endsWith(".properties") ||
            lower.endsWith(".sql") || lower.endsWith(".ts") || lower.endsWith(".tsx") ||
            lower.endsWith(".jsx")) {
            return "text/plain";
        }

        // Archives
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".gz") || lower.endsWith(".gzip")) return "application/gzip";
        if (lower.endsWith(".tar")) return "application/x-tar";
        if (lower.endsWith(".7z")) return "application/x-7z-compressed";

        // Media (Audio / Video)
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".ogg")) return "audio/ogg";

        return "application/octet-stream";
    }

    static boolean isBinaryFile(Path file, String mimeType) {
        if (mimeType != null) {
            if (mimeType.startsWith("image/") && !mimeType.equals("image/svg+xml")) return true;
            if (mimeType.equals("application/pdf")) return true;
            if (mimeType.startsWith("video/") || mimeType.startsWith("audio/")) return true;
            if (mimeType.equals("application/zip") || mimeType.equals("application/gzip") ||
                mimeType.equals("application/x-tar") || mimeType.equals("application/x-7z-compressed")) return true;
            if (mimeType.equals("model/gltf-binary")) return true;
            if (mimeType.startsWith("application/vnd.openxmlformats") || mimeType.startsWith("application/msword") ||
                mimeType.startsWith("application/vnd.ms-")) return true;
        }

        // Sniff first 512 bytes for null byte (common signature of binary formats)
        try {
            if (file != null && Files.isRegularFile(file) && Files.size(file) > 0) {
                try (InputStream is = Files.newInputStream(file)) {
                    byte[] buf = new byte[512];
                    int read = is.read(buf);
                    for (int i = 0; i < read; i++) {
                        if (buf[i] == 0) return true;
                    }
                }
            }
        } catch (Exception ignored) {}

        return false;
    }
}
