package com.mkpro.tools;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Single;

import com.mkpro.CentralMemory;
import com.mkpro.models.McpServer;

import java.awt.Desktop;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class McpServerConnectTools {

    public static final String ANSI_RESET = "\u001b[0m";
    public static final String ANSI_BLUE = "\u001b[34m";
    public static final String ANSI_GREEN = "\u001b[32m";
    public static final String ANSI_YELLOW = "\u001b[33m";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    // Per-server session cache: serverUrl -> sessionId
    private static final Map<String, String> SESSION_CACHE = new HashMap<>();
    private static final Object SESSION_LOCK = new Object();

    /**
     * Quick reachability check — tries to connect within a short timeout.
     * Returns true if the server responded (even with an error), false if unreachable.
     */
    private static void checkServerReachable(String serverUrl) throws Exception {
        try {
            HttpRequest pingReq = HttpRequest.newBuilder()
                    .uri(URI.create(serverUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(8))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"ping\"}"))
                    .build();
            HTTP_CLIENT.send(pingReq, HttpResponse.BodyHandlers.discarding());
        } catch (java.net.http.HttpConnectTimeoutException e) {
            throw new RuntimeException("MCP server is not reachable at " + serverUrl + " (connection timed out). " +
                    "Please check if the server is running.");
        } catch (java.net.ConnectException e) {
            throw new RuntimeException("MCP server is not reachable at " + serverUrl + " (connection refused). " +
                    "Please start the server or check the URL.");
        } catch (java.net.http.HttpTimeoutException e) {
            throw new RuntimeException("MCP server at " + serverUrl + " did not respond in time. " +
                    "The server may be down or overloaded.");
        }
    }

    private static String initializeMcpSession(String serverUrl) throws Exception {
        String initPayload = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{" +
                "\"protocolVersion\":\"2024-11-05\"," +
                "\"capabilities\":{}," +
                "\"clientInfo\":{\"name\":\"mkpro\",\"version\":\"1.5\"}" +
                "}}";

        HttpRequest initReq = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(initPayload))
                .build();

        HttpResponse<String> initResp = HTTP_CLIENT.send(initReq, HttpResponse.BodyHandlers.ofString());
        if (initResp.statusCode() >= 400) {
            throw new RuntimeException("MCP initialize failed (HTTP " + initResp.statusCode() + "): " + initResp.body());
        }

        // Extract session ID from response header
        String sessionId = initResp.headers().firstValue("mcp-session-id")
                .or(() -> initResp.headers().firstValue("Mcp-Session-Id"))
                .orElse(null);

        System.out.println(ANSI_BLUE + "[MCP] Session initialized" +
                (sessionId != null ? " (session=" + sessionId.substring(0, Math.min(8, sessionId.length())) + "...)" : "") +
                ANSI_RESET);

        // Send initialized notification
        String notifPayload = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
        HttpRequest.Builder notifBuilder = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(notifPayload));
        if (sessionId != null) notifBuilder.header("Mcp-Session-Id", sessionId);

        HTTP_CLIENT.send(notifBuilder.build(), HttpResponse.BodyHandlers.ofString());

        return sessionId;
    }

    private static String getOrCreateSession(String serverUrl) throws Exception {
        synchronized (SESSION_LOCK) {
            String cached = SESSION_CACHE.get(serverUrl);
            if (cached != null) return cached;

            String sessionId = initializeMcpSession(serverUrl);
            if (sessionId != null) SESSION_CACHE.put(serverUrl, sessionId);
            return sessionId;
        }
    }

    private static String sendMcpRequest(String serverUrl, String jsonRpcPayload) throws Exception {
        return sendMcpRequest(serverUrl, jsonRpcPayload, 120);
    }

    private static String sendMcpRequest(String serverUrl, String jsonRpcPayload, int timeoutSeconds) throws Exception {
        String sessionId;
        try {
            sessionId = getOrCreateSession(serverUrl);
        } catch (Exception e) {
            System.out.println(ANSI_YELLOW + "[MCP] Session init failed, trying direct request: " + e.getMessage() + ANSI_RESET);
            sessionId = null;
        }

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(jsonRpcPayload));

        if (sessionId != null) {
            reqBuilder.header("Mcp-Session-Id", sessionId);
        }

        HttpResponse<String> response = HTTP_CLIENT.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

        // If session expired (400/404), re-initialize and retry once
        if (response.statusCode() >= 400) {
            String body = response.body();
            if (body.contains("initialize") || body.contains("session") || body.contains("expired")) {
                System.out.println(ANSI_BLUE + "[MCP] Session expired, re-initializing..." + ANSI_RESET);
                synchronized (SESSION_LOCK) {
                    SESSION_CACHE.remove(serverUrl);
                }
                sessionId = getOrCreateSession(serverUrl);

                HttpRequest.Builder retryBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(serverUrl))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonRpcPayload));
                if (sessionId != null) retryBuilder.header("Mcp-Session-Id", sessionId);

                response = HTTP_CLIENT.send(retryBuilder.build(), HttpResponse.BodyHandlers.ofString());
            }

            if (response.statusCode() >= 400) {
                throw new RuntimeException("MCP server HTTP " + response.statusCode() + ": " + response.body());
            }
        }

        String respBody = response.body();

        // Handle SSE response: extract JSON from "data: {...}" lines
        if (respBody.contains("data: {")) {
            StringBuilder jsonParts = new StringBuilder();
            for (String line : respBody.split("\n")) {
                line = line.trim();
                if (line.startsWith("data: ")) {
                    jsonParts.append(line.substring(6));
                }
            }
            if (jsonParts.length() > 0) return jsonParts.toString();
        }

        return respBody;
    }

    public static BaseTool createListMcpServersTool(CentralMemory centralMemory) {
        return new BaseTool(
                "list_mcp_servers",
                "Lists all configured MCP servers with their status, type, and URL. " +
                "Shows which servers are enabled and available for use."
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
                    List<McpServer> servers = centralMemory.getMcpServers();
                    if (servers.isEmpty()) {
                        return Collections.singletonMap("servers", "No MCP servers configured. Use /mcp command to add servers.");
                    }
                    StringBuilder sb = new StringBuilder("Configured MCP Servers:\n");
                    for (int i = 0; i < servers.size(); i++) {
                        McpServer s = servers.get(i);
                        sb.append(String.format("  [%d] %s  %-12s %-6s %s\n",
                            i + 1, s.isEnabled() ? "ON " : "OFF", s.getName(), s.getType(), s.getUrl()));
                    }
                    return Collections.singletonMap("servers", sb.toString());
                });
            }
        };
    }

    public static BaseTool createMcpConnectTool(CentralMemory centralMemory) {
        return new BaseTool(
                "mcp_server_connect",
                "Connects to an MCP server and performs actions. Can use a configured server by name, " +
                "or connect to a URL directly. Actions: list_tools, call_tool, get_resources."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "server_name_or_url", Schema.builder()
                                                .type("STRING")
                                                .description("Either a configured server name (from /mcp list) or a direct MCP server URL")
                                                .build(),
                                        "action", Schema.builder()
                                                .type("STRING")
                                                .description("Action: 'list_tools', 'call_tool', or 'get_resources'")
                                                .build(),
                                        "tool_name", Schema.builder()
                                                .type("STRING")
                                                .description("MCP tool name to call (required for 'call_tool')")
                                                .build(),
                                        "tool_args", Schema.builder()
                                                .type("STRING")
                                                .description("JSON string of arguments for the tool call")
                                                .build()
                                ))
                                .required(ImmutableList.of("server_name_or_url", "action"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String serverRef = (String) args.get("server_name_or_url");
                String action = (String) args.get("action");
                String toolName = args.containsKey("tool_name") ? (String) args.get("tool_name") : null;
                String toolArgs = args.containsKey("tool_args") ? (String) args.get("tool_args") : "{}";

                return Single.fromCallable(() -> {
                    String serverUrl = resolveServerUrl(serverRef, centralMemory);
                    if (serverUrl == null) {
                        boolean hasDisabled = centralMemory.getMcpServers().stream()
                                .anyMatch(s -> !s.isEnabled());
                        String msg = hasDisabled
                                ? "No enabled MCP server found matching '" + serverRef + "'. The server may be disabled — enable it via /mcp → [T] Toggle."
                                : "No MCP server found matching '" + serverRef + "'. Add one using /mcp → [A] Add new server.";
                        System.out.println(ANSI_YELLOW + "[MCP] ✗ " + msg + ANSI_RESET);
                        return Collections.singletonMap("error", msg);
                    }
                    System.out.println(ANSI_BLUE + "[MCP] → " + serverUrl + " | " + action + ANSI_RESET);

                    try {
                        checkServerReachable(serverUrl);
                    } catch (Exception e) {
                        System.out.println(ANSI_YELLOW + "[MCP] ✗ " + e.getMessage() + ANSI_RESET);
                        return Collections.singletonMap("error", e.getMessage());
                    }

                    try {
                        String payload;
                        switch (action) {
                            case "list_tools":
                                payload = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}";
                                break;
                            case "call_tool":
                                if (toolName == null || toolName.isEmpty()) {
                                    return Collections.singletonMap("error", "tool_name is required for call_tool");
                                }
                                payload = String.format(
                                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"%s\",\"arguments\":%s}}",
                                    toolName, toolArgs);
                                break;
                            case "get_resources":
                                payload = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/list\",\"params\":{}}";
                                break;
                            default:
                                return Collections.singletonMap("error", "Unknown action: " + action);
                        }

                        String body = sendMcpRequest(serverUrl, payload);
                        if (body.length() > 50000) body = body.substring(0, 50000) + "\n...[truncated]";

                        Map<String, Object> result = new HashMap<>();
                        result.put("status", "connected");
                        result.put("server_url", serverUrl);
                        result.put("response", body);
                        System.out.println(ANSI_GREEN + "[MCP] ✓ Connected" + ANSI_RESET);
                        return result;
                    } catch (Exception e) {
                        System.out.println(ANSI_YELLOW + "[MCP] ✗ " + e.getMessage() + ANSI_RESET);
                        return Collections.singletonMap("error", "MCP connection failed: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createMcpFetchDesignTool(CentralMemory centralMemory) {
        return new BaseTool(
                "mcp_fetch_design",
                "Fetches ALL design data from a Figma MCP server: design_context, metadata, AND screenshot. " +
                "The design data serves as a visual/structural REFERENCE — the user's prompt determines " +
                "the output platform (HTML, Android XML, iOS SwiftUI, React, Flutter, Kotlin, etc.). " +
                "Pass the full Figma URL and optionally specify the target platform for optimized context."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "figma_url", Schema.builder()
                                                .type("STRING")
                                                .description("The full Figma design URL (e.g. https://figma.com/design/abc/file?node-id=123-456). " +
                                                        "ALWAYS pass the complete URL the user provided.")
                                                .build(),
                                        "node_id", Schema.builder()
                                                .type("STRING")
                                                .description("Optional explicit Figma node ID (e.g. '3600:131719'). Auto-extracted from URL if omitted.")
                                                .build(),
                                        "target_platform", Schema.builder()
                                                .type("STRING")
                                                .description("Target platform inferred from user's prompt: 'web' (HTML/CSS/JS), 'android' (XML/Kotlin/Compose), " +
                                                        "'ios' (SwiftUI/UIKit), 'react' (JSX/TSX), 'flutter' (Dart), or 'general'. Defaults to 'general'.")
                                                .build()
                                ))
                                .required(ImmutableList.of("figma_url"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String input = (String) args.get("figma_url");
                String nodeIdArg = args.containsKey("node_id") ? (String) args.get("node_id") : "";
                if (nodeIdArg == null) nodeIdArg = "";
                String platform = args.containsKey("target_platform") ? (String) args.get("target_platform") : "general";
                if (platform == null) platform = "general";

                final String finalInput = input;
                final String finalNodeIdArg = nodeIdArg;
                final String finalPlatform = platform;

                return Single.fromCallable(() -> {
                    Map<String, Object> result = new HashMap<>();
                    long startTime = System.currentTimeMillis();

                    // ── Step 1: Resolve node ID ──
                    progress("Step 1/5: Resolving node ID from URL...");
                    String effectiveNodeId = finalNodeIdArg;
                    if (effectiveNodeId.isEmpty()) {
                        String extracted = extractNodeIdFromUrl(finalInput);
                        if (extracted != null) effectiveNodeId = extracted;
                    }
                    if (effectiveNodeId.isEmpty()) {
                        return Collections.singletonMap("error", "Could not extract node-id from URL: " + finalInput +
                            ". Ensure the URL contains ?node-id=XXX-YYY");
                    }
                    progress("  ✓ Node ID: " + effectiveNodeId);
                    result.put("node_id", effectiveNodeId);
                    result.put("target_platform", finalPlatform);

                    String clientLangs = platformToClientLanguages(finalPlatform);

                    // ── Step 2: Resolve MCP server ──
                    progress("Step 2/5: Resolving Figma MCP server...");
                    String serverUrl = resolveFigmaServerUrl(finalInput, centralMemory);

                    if (serverUrl == null) {
                        // Check if a FIGMA server exists but is disabled
                        boolean hasFigmaDisabled = centralMemory.getMcpServers().stream()
                                .anyMatch(s -> s.getType() == McpServer.McpType.FIGMA && !s.isEnabled());
                        if (hasFigmaDisabled) {
                            progress("  ✗ Figma MCP server is DISABLED. Enable it via /mcp → [T] Toggle.");
                            return Collections.singletonMap("error",
                                    "Figma MCP server is configured but currently DISABLED. " +
                                    "Please enable it using /mcp → [T] Toggle enable/disable, then try again.");
                        }
                        progress("  ✗ No Figma MCP server configured.");
                        return Collections.singletonMap("error",
                                "No Figma MCP server is configured. " +
                                "Add one using /mcp → [A] Add new server.");
                    }
                    progress("  ✓ Server: " + serverUrl);
                    result.put("server_url", serverUrl);

                    // ── Step 2b: Quick reachability check ──
                    progress("  Checking server connectivity...");
                    try {
                        checkServerReachable(serverUrl);
                        progress("  ✓ Server is reachable");
                    } catch (Exception e) {
                        progress("  ✗ " + e.getMessage());
                        return Collections.singletonMap("error", e.getMessage());
                    }

                    // ── Step 3: Fetch design_context (heavy call, 120s timeout) ──
                    progress("Step 3/5: Fetching design context (this may take up to 2 min)...");
                    try {
                        String dcArgs = buildFigmaArgs(effectiveNodeId, clientLangs, true);
                        String dcPayload = String.format(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_design_context\",\"arguments\":%s}}", dcArgs);
                        String dcBody = sendMcpRequest(serverUrl, dcPayload, 120);
                        if (dcBody.length() > 80000) dcBody = dcBody.substring(0, 80000) + "\n...[truncated]";
                        result.put("design_context", dcBody);
                        progress("  ✓ Design context fetched (" + dcBody.length() + " chars)");
                    } catch (Exception e) {
                        progress("  ✗ Design context failed: " + e.getMessage() + " (will proceed with metadata + screenshot)");
                        result.put("design_context_error", e.getMessage());
                    }

                    // ── Step 4: Fetch metadata ──
                    progress("Step 4/5: Fetching metadata (component names, dimensions, colors)...");
                    try {
                        String mdArgs = buildFigmaArgs(effectiveNodeId, clientLangs, false);
                        String mdPayload = String.format(
                            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"get_metadata\",\"arguments\":%s}}", mdArgs);
                        String mdBody = sendMcpRequest(serverUrl, mdPayload, 60);
                        if (mdBody.length() > 40000) mdBody = mdBody.substring(0, 40000) + "\n...[truncated]";
                        result.put("metadata", mdBody);
                        progress("  ✓ Metadata fetched (" + mdBody.length() + " chars)");
                    } catch (Exception e) {
                        progress("  ✗ Metadata failed: " + e.getMessage());
                        result.put("metadata_error", e.getMessage());
                    }

                    // ── Step 5: Fetch screenshot ──
                    progress("Step 5/5: Fetching screenshot (visual reference)...");
                    try {
                        String ssArgs = String.format("{\"nodeId\":\"%s\",\"clientLanguages\":\"%s\"}", effectiveNodeId, clientLangs);
                        String ssPayload = String.format(
                            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"get_screenshot\",\"arguments\":%s}}", ssArgs);
                        String ssBody = sendMcpRequest(serverUrl, ssPayload, 60);
                        if (ssBody.length() > 80000) ssBody = ssBody.substring(0, 80000) + "\n...[truncated]";
                        result.put("screenshot", ssBody);
                        progress("  ✓ Screenshot fetched (" + ssBody.length() + " chars)");
                    } catch (Exception e) {
                        progress("  ✗ Screenshot failed: " + e.getMessage());
                        result.put("screenshot_error", e.getMessage());
                    }

                    long elapsed = System.currentTimeMillis() - startTime;
                    boolean hasDesign = result.containsKey("design_context");
                    boolean hasMeta = result.containsKey("metadata");
                    boolean hasScreenshot = result.containsKey("screenshot");
                    boolean hasAnyData = hasDesign || hasMeta || hasScreenshot;

                    result.put("status", hasAnyData ? "success" : "failed");
                    result.put("elapsed_ms", elapsed);
                    result.put("figma_url", finalInput);

                    String summary = String.format("Fetched %s%s%sfor [%s] in %.1fs",
                        hasDesign ? "design_context " : "",
                        hasMeta ? "metadata " : "",
                        hasScreenshot ? "screenshot " : "",
                        finalPlatform,
                        elapsed / 1000.0);
                    result.put("summary", summary);
                    progress("━━━ " + summary + " ━━━");

                    // Embed instruction in the result so agent knows what to do
                    StringBuilder instruction = new StringBuilder();
                    instruction.append("INSTRUCTION: You have design data. ");
                    if (hasDesign) instruction.append("design_context contains full layout/styles. ");
                    if (hasMeta) instruction.append("metadata has component info. ");
                    if (hasScreenshot) instruction.append("screenshot has the visual reference. ");
                    instruction.append("NOW generate the code for target_platform=").append(finalPlatform).append(". ");
                    instruction.append("Use ALL the data you received. Do NOT ask the user for design details. ");
                    instruction.append("Do NOT call mcp_fetch_design again. ");
                    instruction.append("Generate the code and save it with save_component (it auto-detects the project type and saves to the correct directory). ");
                    instruction.append("Just provide the filename — save_component handles the path automatically.");
                    result.put("next_action", instruction.toString());

                    return result;
                });
            }
        };
    }

    public static BaseTool createSaveComponentTool() {
        return new BaseTool(
                "save_component",
                "Saves generated code to the correct location by auto-detecting the project type. " +
                "Scans the current working directory to detect Android, iOS, React, Flutter, Web, or Java projects " +
                "and places the file in the appropriate source directory. " +
                "Falls back to sample/ if the project type is unknown."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "filename", Schema.builder()
                                                .type("STRING")
                                                .description("Filename with extension (e.g. 'SearchScreen.kt', 'SearchView.swift', " +
                                                        "'search_page.dart', 'SearchResults.tsx', 'search-results.html'). " +
                                                        "The tool will auto-detect the project and save to the correct directory.")
                                                .build(),
                                        "content", Schema.builder()
                                                .type("STRING")
                                                .description("The complete file content to save.")
                                                .build()
                                ))
                                .required(ImmutableList.of("filename", "content"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                final String fname = (String) args.get("filename");
                final String content = (String) args.get("content");

                return Single.fromCallable(() -> {
                    try {
                        Path cwd = Paths.get("").toAbsolutePath();
                        ProjectInfo project = detectProject(cwd);

                        progress("Detected project: " + project.type + " at " + project.root);

                        Path filePath = resolveOutputPath(project, fname);
                        Files.createDirectories(filePath.getParent());
                        Files.writeString(filePath, content);

                        String relativePath = cwd.relativize(filePath).toString();
                        progress("Saved → " + relativePath + " (" + content.length() + " chars)");

                        Map<String, Object> result = new HashMap<>();
                        result.put("status", "saved");
                        result.put("path", filePath.toAbsolutePath().toString());
                        result.put("relative_path", relativePath);
                        result.put("filename", fname);
                        result.put("project_type", project.type);
                        result.put("size_bytes", content.length());
                        return result;
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Failed to save: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createOpenComponentPreviewTool() {
        return new BaseTool(
                "open_component_preview",
                "Returns the absolute path of a saved component file so the user can open it manually. " +
                "Does NOT open the file automatically. Accepts a full path or just a filename (searches the project for it)."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "file_path", Schema.builder()
                                                .type("STRING")
                                                .description("Relative or absolute path to the file. " +
                                                        "Use the path returned by save_component.")
                                                .build()
                                ))
                                .required(ImmutableList.of("file_path"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                final String fpath = (String) args.get("file_path");
                return Single.fromCallable(() -> {
                    try {
                        Path filePath = Paths.get(fpath);
                        if (!Files.exists(filePath)) {
                            filePath = Paths.get("").toAbsolutePath().resolve(fpath);
                        }
                        if (!Files.exists(filePath)) {
                            return Collections.singletonMap("error", "File not found: " + fpath);
                        }

                        String absPath = filePath.toAbsolutePath().toString();
                        long size = Files.size(filePath);
                        progress("File ready: " + absPath + " (" + size + " bytes)");

                        Map<String, Object> result = new HashMap<>();
                        result.put("status", "ready");
                        result.put("path", absPath);
                        result.put("size_bytes", size);
                        result.put("message", "File saved at: " + absPath);
                        return result;
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Failed: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createScanProjectTool() {
        return new BaseTool(
                "scan_project",
                "Scans the current working directory to detect the project type (Android, iOS, React, Flutter, Web, etc.) " +
                "and lists the key directories. Use this BEFORE generating code to understand where files should go."
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
                    try {
                        Path cwd = Paths.get("").toAbsolutePath();
                        ProjectInfo project = detectProject(cwd);

                        progress("Project scan: " + project.type);

                        Map<String, Object> result = new HashMap<>();
                        result.put("project_type", project.type);
                        result.put("root", project.root.toString());
                        if (project.packageName != null) result.put("package_name", project.packageName);

                        Map<String, String> dirMap = new HashMap<>();
                        project.directories.forEach((k, v) -> dirMap.put(k, cwd.relativize(v).toString()));
                        result.put("directories", dirMap.toString());
                        result.put("summary", project.toString());

                        result.put("hint", "save_component will auto-detect this project and save files to the correct directory. " +
                                "Just call save_component with the filename — no need to specify the full path.");
                        return result;
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Scan failed: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createListComponentsTool() {
        return new BaseTool(
                "list_components",
                "Lists generated component files. Checks project-specific directories and sample/ folder."
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
                    try {
                        Path cwd = Paths.get("").toAbsolutePath();
                        ProjectInfo project = detectProject(cwd);
                        StringBuilder sb = new StringBuilder("Project: " + project.type + "\n");

                        // List files in each detected directory
                        project.directories.forEach((name, dir) -> {
                            if (Files.exists(dir) && Files.isDirectory(dir)) {
                                sb.append("\n[").append(name).append(": ").append(cwd.relativize(dir)).append("]\n");
                                try {
                                    Files.list(dir)
                                        .filter(p -> !Files.isDirectory(p))
                                        .sorted()
                                        .limit(20)
                                        .forEach(p -> {
                                            long size = 0;
                                            try { size = Files.size(p); } catch (Exception ignored) {}
                                            sb.append("  • ").append(p.getFileName()).append(" (").append(size).append(" bytes)\n");
                                        });
                                } catch (Exception ignored) {}
                            }
                        });

                        // Also check sample/ as fallback
                        Path sampleDir = cwd.resolve("sample");
                        if (Files.exists(sampleDir) && Files.isDirectory(sampleDir)) {
                            sb.append("\n[sample/]\n");
                            listFilesRecursive(sampleDir, sampleDir, sb);
                        }

                        return Collections.singletonMap("components", sb.toString());
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Error: " + e.getMessage());
                    }
                });
            }
        };
    }

    // ── Project Detection ─────────────────────────────────────────────

    public static class ProjectInfo {
        public final String type;        // android, ios, react, flutter, web, nextjs, vue, angular, java_maven, java_gradle, unknown
        public final Path root;          // project root directory
        public final String packageName; // e.g. com.example.app (for Android/Java)
        public final Map<String, Path> directories; // key paths like "sources", "resources", "layouts", "components"

        ProjectInfo(String type, Path root, String packageName, Map<String, Path> directories) {
            this.type = type;
            this.root = root;
            this.packageName = packageName;
            this.directories = directories;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Project: ").append(type).append("\n");
            sb.append("Root: ").append(root).append("\n");
            if (packageName != null) sb.append("Package: ").append(packageName).append("\n");
            sb.append("Directories:\n");
            directories.forEach((k, v) -> sb.append("  ").append(k).append(" → ").append(root.relativize(v)).append("\n"));
            return sb.toString();
        }
    }

    public static ProjectInfo detectProject(Path cwd) {
        Map<String, Path> dirs = new HashMap<>();

        // ── Android (Gradle + app module) ──
        if (Files.exists(cwd.resolve("app/build.gradle")) || Files.exists(cwd.resolve("app/build.gradle.kts"))) {
            String pkg = findAndroidPackage(cwd);
            Path kotlinSrc = findExistingDir(cwd, "app/src/main/kotlin", "app/src/main/java");
            if (kotlinSrc == null) {
                kotlinSrc = cwd.resolve("app/src/main/java");
            }
            Path resSrc = cwd.resolve("app/src/main/res");
            dirs.put("sources", kotlinSrc);
            if (pkg != null) {
                Path pkgDir = kotlinSrc.resolve(pkg.replace('.', '/'));
                dirs.put("package", pkgDir);
                Path uiDir = findExistingDir(pkgDir, "ui", "presentation", "view", "compose", "screen", "screens");
                if (uiDir != null) {
                    dirs.put("ui", uiDir);
                } else {
                    dirs.put("ui", pkgDir.resolve("ui"));
                }
            }
            dirs.put("resources", resSrc);
            dirs.put("layouts", resSrc.resolve("layout"));
            dirs.put("drawables", resSrc.resolve("drawable"));
            dirs.put("values", resSrc.resolve("values"));
            return new ProjectInfo("android", cwd, pkg, dirs);
        }

        // ── Flutter ──
        if (Files.exists(cwd.resolve("pubspec.yaml"))) {
            Path libDir = cwd.resolve("lib");
            dirs.put("sources", libDir);
            Path screensDir = findExistingDir(libDir, "screens", "pages", "views", "ui");
            if (screensDir != null) dirs.put("screens", screensDir);
            Path widgetsDir = findExistingDir(libDir, "widgets", "components");
            if (widgetsDir != null) dirs.put("widgets", widgetsDir);
            return new ProjectInfo("flutter", cwd, null, dirs);
        }

        // ── iOS (Xcode) ──
        try (Stream<Path> s = Files.list(cwd)) {
            boolean hasXcode = s.anyMatch(p -> p.toString().endsWith(".xcodeproj") || p.toString().endsWith(".xcworkspace"));
            if (hasXcode || Files.exists(cwd.resolve("Package.swift"))) {
                Path srcDir = findIosSourceDir(cwd);
                if (srcDir == null) srcDir = cwd;
                dirs.put("sources", srcDir);
                Path viewsDir = findExistingDir(srcDir, "Views", "View", "Screens", "UI");
                if (viewsDir != null) {
                    dirs.put("views", viewsDir);
                } else {
                    dirs.put("views", srcDir.resolve("Views"));
                }
                return new ProjectInfo("ios", cwd, null, dirs);
            }
        } catch (Exception ignored) {}

        // ── React / Next.js / Vue / Angular (package.json) ──
        if (Files.exists(cwd.resolve("package.json"))) {
            String pkgJson = readFileQuiet(cwd.resolve("package.json"));
            String webType = "web";
            if (pkgJson.contains("\"next\"")) webType = "nextjs";
            else if (pkgJson.contains("\"react\"")) webType = "react";
            else if (pkgJson.contains("\"vue\"")) webType = "vue";
            else if (pkgJson.contains("\"@angular/core\"")) webType = "angular";

            Path srcDir = findExistingDir(cwd, "src", "app");
            if (srcDir == null) srcDir = cwd.resolve("src");
            dirs.put("sources", srcDir);
            Path compDir = findExistingDir(srcDir, "components", "views", "pages", "screens");
            if (compDir != null) {
                dirs.put("components", compDir);
            } else {
                dirs.put("components", srcDir.resolve("components"));
            }
            Path publicDir = findExistingDir(cwd, "public", "static");
            if (publicDir == null) publicDir = cwd.resolve("public");
            dirs.put("public", publicDir);
            return new ProjectInfo(webType, cwd, null, dirs);
        }

        // ── Java/Maven ──
        if (Files.exists(cwd.resolve("pom.xml"))) {
            Path javaSrc = findExistingDir(cwd, "src/main/java", "src/main/kotlin");
            if (javaSrc != null) dirs.put("sources", javaSrc);
            Path resSrc = cwd.resolve("src/main/resources");
            if (Files.exists(resSrc)) dirs.put("resources", resSrc);
            Path webDir = findExistingDir(resSrc, "web", "static", "public", "templates");
            if (webDir != null) dirs.put("web", webDir);
            return new ProjectInfo("java_maven", cwd, null, dirs);
        }

        // ── Java/Gradle (non-Android) ──
        if (Files.exists(cwd.resolve("build.gradle")) || Files.exists(cwd.resolve("build.gradle.kts"))) {
            Path javaSrc = findExistingDir(cwd, "src/main/java", "src/main/kotlin");
            if (javaSrc != null) dirs.put("sources", javaSrc);
            return new ProjectInfo("java_gradle", cwd, null, dirs);
        }

        // ── Plain web (index.html exists) ──
        if (Files.exists(cwd.resolve("index.html"))) {
            dirs.put("sources", cwd);
            return new ProjectInfo("web", cwd, null, dirs);
        }

        // ── Unknown ──
        dirs.put("sources", cwd);
        return new ProjectInfo("unknown", cwd, null, dirs);
    }

    /**
     * Given a detected project and a filename, compute the best save path.
     */
    public static Path resolveOutputPath(ProjectInfo project, String filename) {
        String ext = "";
        int dot = filename.lastIndexOf('.');
        if (dot >= 0) ext = filename.substring(dot).toLowerCase();
        String baseName = dot >= 0 ? filename.substring(0, dot) : filename;

        switch (project.type) {
            case "android": {
                if (ext.equals(".kt") || ext.equals(".java")) {
                    Path target = project.directories.get("ui");
                    if (target == null) target = project.directories.get("package");
                    if (target == null) {
                        Path sources = project.directories.getOrDefault("sources", project.root.resolve("app/src/main/java"));
                        target = findDeepestPackageDir(sources);
                    }
                    return target.resolve(filename);
                }
                if (ext.equals(".xml") && (baseName.startsWith("activity_") || baseName.startsWith("fragment_") ||
                        baseName.startsWith("layout_") || baseName.startsWith("item_"))) {
                    return project.directories.getOrDefault("layouts",
                            project.root.resolve("app/src/main/res/layout")).resolve(filename);
                }
                if (ext.equals(".xml")) {
                    return project.directories.getOrDefault("drawables",
                            project.root.resolve("app/src/main/res/drawable")).resolve(filename);
                }
                return project.directories.getOrDefault("sources", project.root.resolve("app/src/main/java")).resolve(filename);
            }
            case "flutter": {
                if (ext.equals(".dart")) {
                    Path target = project.directories.getOrDefault("screens",
                            project.directories.getOrDefault("sources", project.root.resolve("lib")));
                    return target.resolve(filename);
                }
                return project.root.resolve("lib").resolve(filename);
            }
            case "ios": {
                if (ext.equals(".swift")) {
                    Path target = project.directories.getOrDefault("views",
                            project.directories.getOrDefault("sources", project.root));
                    return target.resolve(filename);
                }
                return project.directories.getOrDefault("sources", project.root).resolve(filename);
            }
            case "react":
            case "nextjs":
            case "vue":
            case "angular": {
                if (ext.equals(".tsx") || ext.equals(".jsx") || ext.equals(".vue") || ext.equals(".ts") || ext.equals(".js")) {
                    Path target = project.directories.getOrDefault("components",
                            project.directories.getOrDefault("sources", project.root.resolve("src")));
                    return target.resolve(filename);
                }
                if (ext.equals(".css") || ext.equals(".scss")) {
                    Path target = project.directories.getOrDefault("components",
                            project.directories.getOrDefault("sources", project.root.resolve("src")));
                    return target.resolve(filename);
                }
                if (ext.equals(".html")) {
                    return project.directories.getOrDefault("public", project.root.resolve("public")).resolve(filename);
                }
                return project.directories.getOrDefault("sources", project.root.resolve("src")).resolve(filename);
            }
            case "java_maven":
            case "java_gradle": {
                if (ext.equals(".html") || ext.equals(".css") || ext.equals(".js")) {
                    return project.directories.getOrDefault("web",
                            project.directories.getOrDefault("resources", project.root.resolve("src/main/resources"))).resolve(filename);
                }
                if (ext.equals(".java") || ext.equals(".kt")) {
                    return project.directories.getOrDefault("sources", project.root.resolve("src/main/java")).resolve(filename);
                }
                return project.directories.getOrDefault("resources", project.root.resolve("src/main/resources")).resolve(filename);
            }
            case "web": {
                return project.directories.getOrDefault("sources", project.root).resolve(filename);
            }
            default:
                return project.root.resolve(filename);
        }
    }

    // ── Project detection helpers ──

    private static String findAndroidPackage(Path root) {
        // 1. Try AndroidManifest.xml (older projects)
        Path manifest = root.resolve("app/src/main/AndroidManifest.xml");
        if (Files.exists(manifest)) {
            String content = readFileQuiet(manifest);
            if (content != null) {
                int idx = content.indexOf("package=\"");
                if (idx >= 0) {
                    int start = idx + 9;
                    int end = content.indexOf("\"", start);
                    if (end > start) return content.substring(start, end);
                }
            }
        }

        // 2. Try build.gradle / build.gradle.kts namespace (AGP 7+)
        for (String gradleFile : new String[]{"app/build.gradle.kts", "app/build.gradle"}) {
            Path gradle = root.resolve(gradleFile);
            if (Files.exists(gradle)) {
                String content = readFileQuiet(gradle);
                if (content != null) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("namespace\\s*[=(]\\s*\"([^\"]+)\"")
                            .matcher(content);
                    if (m.find()) return m.group(1);

                    m = java.util.regex.Pattern
                            .compile("applicationId\\s*[=(]\\s*\"([^\"]+)\"")
                            .matcher(content);
                    if (m.find()) return m.group(1);
                }
            }
        }

        // 3. Scan existing source files for package declaration
        Path srcDir = findExistingDir(root, "app/src/main/kotlin", "app/src/main/java");
        if (srcDir != null) {
            try (Stream<Path> walk = Files.walk(srcDir, 6)) {
                Optional<String> pkg = walk
                        .filter(p -> p.toString().endsWith(".kt") || p.toString().endsWith(".java"))
                        .limit(5)
                        .map(p -> readFileQuiet(p))
                        .filter(c -> c != null)
                        .map(c -> {
                            java.util.regex.Matcher m = java.util.regex.Pattern
                                    .compile("^package\\s+([\\w.]+)")
                                    .matcher(c);
                            return m.find() ? m.group(1) : null;
                        })
                        .filter(p -> p != null)
                        .findFirst();
                if (pkg.isPresent()) return pkg.get();
            } catch (Exception ignored) {}
        }

        return null;
    }

    /**
     * Walks the source directory to find the deepest package folder containing .kt or .java files.
     * This handles cases where the package name couldn't be determined from config files.
     */
    private static Path findDeepestPackageDir(Path sourcesRoot) {
        if (!Files.isDirectory(sourcesRoot)) return sourcesRoot;
        try (Stream<Path> walk = Files.walk(sourcesRoot, 8)) {
            return walk
                    .filter(Files::isDirectory)
                    .filter(d -> {
                        try (Stream<Path> files = Files.list(d)) {
                            return files.anyMatch(f -> {
                                String name = f.getFileName().toString();
                                return name.endsWith(".kt") || name.endsWith(".java");
                            });
                        } catch (Exception e) { return false; }
                    })
                    .reduce((a, b) -> a.getNameCount() >= b.getNameCount() ? a : b)
                    .orElse(sourcesRoot);
        } catch (Exception e) {
            return sourcesRoot;
        }
    }

    private static Path findIosSourceDir(Path root) {
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return !name.startsWith(".") && !name.equals("Pods") && !name.equals("build") &&
                               !name.endsWith(".xcodeproj") && !name.endsWith(".xcworkspace");
                    })
                    .filter(p -> {
                        try (Stream<Path> inner = Files.walk(p, 2)) {
                            return inner.anyMatch(f -> f.toString().endsWith(".swift"));
                        } catch (Exception e) { return false; }
                    })
                    .findFirst().orElse(null);
        } catch (Exception e) { return null; }
    }

    private static Path findExistingDir(Path base, String... candidates) {
        for (String c : candidates) {
            Path p = base.resolve(c);
            if (Files.isDirectory(p)) return p;
        }
        return null;
    }

    private static String readFileQuiet(Path path) {
        try { return Files.readString(path); } catch (Exception e) { return null; }
    }

    // ── General Helpers ──────────────────────────────────────────────

    private static void progress(String msg) {
        System.out.println(ANSI_BLUE + "[MCP-Figma] " + msg + ANSI_RESET);
        System.out.flush();
    }

    private static String platformToClientLanguages(String platform) {
        switch (platform.toLowerCase()) {
            case "android":  return "kotlin,xml,java";
            case "ios":      return "swift,swiftui,objective-c";
            case "react":    return "typescript,javascript,jsx,tsx,css";
            case "flutter":  return "dart";
            case "web":      return "html,css,javascript";
            default:         return "html,css,javascript,kotlin,swift,dart";
        }
    }

    private static String buildFigmaArgs(String nodeId, String clientLanguages, boolean isDesignContext) {
        StringBuilder sb = new StringBuilder("{");
        if (!nodeId.isEmpty()) {
            sb.append("\"nodeId\":\"").append(nodeId).append("\",");
        }
        sb.append("\"clientLanguages\":\"").append(clientLanguages).append("\",\"clientFrameworks\":\"unknown\"");
        if (isDesignContext) {
            sb.append(",\"artifactType\":\"WEB_PAGE_OR_APP_SCREEN\",\"taskType\":\"CREATE_ARTIFACT\",\"forceCode\":true");
        }
        sb.append("}");
        return sb.toString();
    }

    private static void listFilesRecursive(Path root, Path dir, StringBuilder sb) throws Exception {
        Files.list(dir).sorted().forEach(p -> {
            String relative = root.relativize(p).toString();
            if (Files.isDirectory(p)) {
                sb.append("  📁 ").append(relative).append("/\n");
                try { listFilesRecursive(root, p, sb); } catch (Exception ignored) {}
            } else {
                long size = 0;
                try { size = Files.size(p); } catch (Exception ignored) {}
                sb.append("  • ").append(relative).append(" (").append(size).append(" bytes)\n");
            }
        });
    }

    private static String resolveServerUrl(String ref, CentralMemory centralMemory) {
        // Direct URL - use as-is
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref;

        List<McpServer> enabledServers = centralMemory.getEnabledMcpServers();

        // Match by name (case-insensitive, exact then partial) — only enabled servers
        for (McpServer s : enabledServers) {
            if (s.getName().equalsIgnoreCase(ref)) return s.getUrl();
        }
        for (McpServer s : enabledServers) {
            if (s.getName().toLowerCase().contains(ref.toLowerCase())) return s.getUrl();
        }

        // Match by type — only enabled servers
        try {
            McpServer.McpType type = McpServer.McpType.valueOf(ref.toUpperCase());
            for (McpServer s : enabledServers) {
                if (s.getType() == type) return s.getUrl();
            }
        } catch (IllegalArgumentException ignored) {}

        // Last resort: return first enabled server
        if (!enabledServers.isEmpty()) return enabledServers.get(0).getUrl();

        return null;
    }

    private static String resolveFigmaServerUrl(String input, CentralMemory centralMemory) {
        // If input is a figma.com URL, find an ENABLED FIGMA-type MCP server
        if (input.contains("figma.com") || input.contains("figma.dev")) {
            List<McpServer> enabledServers = centralMemory.getEnabledMcpServers();
            for (McpServer s : enabledServers) {
                if (s.getType() == McpServer.McpType.FIGMA) return s.getUrl();
            }
            // Check if a Figma server exists but is disabled — give a clear message
            for (McpServer s : centralMemory.getMcpServers()) {
                if (s.getType() == McpServer.McpType.FIGMA && !s.isEnabled()) {
                    return null; // caller handles null as "server disabled"
                }
            }
        }
        return resolveServerUrl(input, centralMemory);
    }

    private static String extractNodeIdFromUrl(String url) {
        try {
            if (url.contains("node-id=")) {
                String nodeIdParam = url.substring(url.indexOf("node-id=") + 8);
                if (nodeIdParam.contains("&")) nodeIdParam = nodeIdParam.substring(0, nodeIdParam.indexOf("&"));
                // Figma URLs use dashes, API uses colons
                return nodeIdParam.replace("-", ":");
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static String buildMcpContextForAgent(CentralMemory centralMemory) {
        List<McpServer> allServers = centralMemory.getMcpServers();
        List<McpServer> enabledServers = centralMemory.getEnabledMcpServers();

        if (allServers.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();

        sb.append("\n\n── MCP (applies ONLY when user provides a Figma URL) ──\n");
        sb.append("These MCP instructions are ONLY relevant when the user's message contains a figma.com/design/ URL.\n");
        sb.append("For all other requests, ignore this section entirely and delegate normally.\n\n");

        if (enabledServers.isEmpty()) {
            sb.append("MCP Status: All servers DISABLED. If user provides a Figma URL, tell them to enable via /mcp.\n");
            return sb.toString();
        }

        sb.append("Enabled MCP servers:\n");
        for (McpServer s : enabledServers) {
            sb.append(String.format("  • %s (%s): %s\n", s.getName(), s.getType(), s.getUrl()));
        }

        sb.append("\nFigma URL workflow:\n");
        sb.append("  1. scan_project → detect project type (android/ios/react/flutter/web)\n");
        sb.append("  2. mcp_fetch_design → fetch design data (call ONCE only)\n");
        sb.append("  3. Analyze the fetched design data yourself. Extract key design tokens:\n");
        sb.append("     colors, fonts, spacing, border radius, component names, layout structure.\n");
        sb.append("  4. Delegate to Coder with a CONCISE instruction:\n");
        sb.append("     - The platform (e.g. 'Kotlin Jetpack Compose' or 'SwiftUI')\n");
        sb.append("     - A summary of design tokens (key colors as hex, font sizes, spacing values)\n");
        sb.append("     - The component/screen name and layout description\n");
        sb.append("     - Tell the Coder to generate code directly — do NOT pass raw design data\n");
        sb.append("  5. After Coder returns code, YOU call save_component to save it\n");
        sb.append("  6. open_component_preview (HTML/web only)\n");
        sb.append("If mcp_fetch_design errors, report to user and stop.\n");
        return sb.toString();
    }
}
