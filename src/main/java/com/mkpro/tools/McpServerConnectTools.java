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

    private static final Map<String, String> SESSION_CACHE = new HashMap<>();
    private static final Object SESSION_LOCK = new Object();

    private static void checkServerReachable(String serverUrl) throws Exception {
        try {
            HttpRequest pingReq = HttpRequest.newBuilder()
                    .uri(URI.create(serverUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(8))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"ping\"}"))
                    .build();
            HTTP_CLIENT.send(pingReq, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            throw new RuntimeException("MCP server is not reachable: " + e.getMessage());
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
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(initPayload))
                .build();

        HttpResponse<String> initResp = HTTP_CLIENT.send(initReq, HttpResponse.BodyHandlers.ofString());
        String sessionId = initResp.headers().firstValue("mcp-session-id").orElse(null);
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

    public static BaseTool createListMcpServersTool(CentralMemory centralMemory) {
        return new BaseTool("list_mcp_servers", "Lists MCP servers.") {
            @Override public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder().name(name()).description(description()).build());
            }
            @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.just(Collections.singletonMap("servers", centralMemory.getEnabledMcpServers()));
            }
        };
    }

    public static class ProjectInfo {
        public String type;
        public String root;
        public List<String> directories;
        @Override public String toString() { return "Type: " + type + "\nRoot: " + root; }
    }

    public static ProjectInfo detectProject(Path path) {
        ProjectInfo info = new ProjectInfo();
        info.root = path.toString();
        if (Files.exists(path.resolve("pom.xml"))) info.type = "java_maven";
        else if (Files.exists(path.resolve("package.json"))) info.type = "node_js";
        else info.type = "unknown";
        return info;
    }

    public static BaseTool createScanProjectTool() {
        return new BaseTool("scan_project", "Scans the project structure.") {
            @Override public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder().name(name()).description(description()).build());
            }
            @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    ProjectInfo info = detectProject(Paths.get(""));
                    return Collections.singletonMap("project_info", info.toString());
                });
            }
        };
    }

    public static BaseTool createSaveComponentTool() {
        return new BaseTool("save_component", "Saves a generated component.") {
            @Override public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder().name(name()).description(description())
                    .parameters(Schema.builder().type("OBJECT")
                        .properties(ImmutableMap.of(
                            "filename", Schema.builder().type("STRING").build(),
                            "content", Schema.builder().type("STRING").build()))
                        .required(ImmutableList.of("filename", "content")).build()).build());
            }
            @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String filename = (String) args.get("filename");
                    String content = (String) args.get("content");
                    Files.writeString(Paths.get(filename), content);
                    return Collections.singletonMap("status", "Saved " + filename);
                });
            }
        };
    }
}
