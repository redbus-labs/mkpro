package com.mkpro.tools;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * McpProtocolClient handles JSON-RPC communication with MCP servers.
 * Responsibilities:
 *   - Server reachability checks
 *   - Session initialization and caching
 *   - Request sending with retry on session expiry
 *   - SSE response parsing
 */
public class McpProtocolClient {

    private static final String ANSI_BLUE = "\u001b[34m";
    private static final String ANSI_YELLOW = "\u001b[33m";
    private static final String ANSI_RESET = "\u001b[0m";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private static final Map<String, String> SESSION_CACHE = new HashMap<>();
    private static final Object SESSION_LOCK = new Object();

    /**
     * Quick reachability check — tries to connect within a short timeout.
     * Throws RuntimeException if the server is unreachable.
     */
    public static void checkServerReachable(String serverUrl) throws Exception {
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
        } catch (Exception e) {
            throw new RuntimeException("MCP server is not reachable: " + e.getMessage());
        }
    }

    /**
     * Initialize a new MCP session with the server.
     * @return The session ID, or null if the server doesn't use sessions.
     */
    public static String initializeMcpSession(String serverUrl) throws Exception {
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

    /**
     * Get or create a cached session for the given server URL.
     */
    public static String getOrCreateSession(String serverUrl) throws Exception {
        synchronized (SESSION_LOCK) {
            String cached = SESSION_CACHE.get(serverUrl);
            if (cached != null) return cached;

            String sessionId = initializeMcpSession(serverUrl);
            if (sessionId != null) SESSION_CACHE.put(serverUrl, sessionId);
            return sessionId;
        }
    }

    /**
     * Send a JSON-RPC request to an MCP server with default 120s timeout.
     */
    public static String sendMcpRequest(String serverUrl, String jsonRpcPayload) throws Exception {
        return sendMcpRequest(serverUrl, jsonRpcPayload, 120);
    }

    /**
     * Send a JSON-RPC request to an MCP server.
     * Handles session management, retry on expiry, and SSE response parsing.
     */
    public static String sendMcpRequest(String serverUrl, String jsonRpcPayload, int timeoutSeconds) throws Exception {
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

    /**
     * Invalidate cached session for a server (useful for forced reconnects).
     */
    public static void invalidateSession(String serverUrl) {
        synchronized (SESSION_LOCK) {
            SESSION_CACHE.remove(serverUrl);
        }
    }
}
