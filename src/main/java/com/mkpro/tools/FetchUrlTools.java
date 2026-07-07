package com.mkpro.tools;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Single;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Provides a tool for fetching content from URLs.
 * Supports GET requests with configurable timeout and response size limits.
 */
public class FetchUrlTools {

    private static final String ANSI_BLUE = "\u001b[34m";
    private static final String ANSI_RESET = "\u001b[0m";

    private static final int MAX_RESPONSE_BYTES = 100 * 1024; // 100KB limit
    private static final int TIMEOUT_SECONDS = 15;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build();

    /**
     * Creates a tool that fetches content from a URL via HTTP GET.
     */
    public static BaseTool create() {
        return new BaseTool(
                "fetch_url",
                "Fetches content from a URL via HTTP GET. Returns the response body as text. " +
                "Use this to read web pages, API responses, documentation, or any HTTP resource. " +
                "Response is truncated to 100KB."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "url", Schema.builder()
                                                .type("STRING")
                                                .description("The URL to fetch (must start with http:// or https://).")
                                                .build(),
                                        "headers", Schema.builder()
                                                .type("STRING")
                                                .description("Optional comma-separated headers in 'Key: Value' format (e.g., 'Authorization: Bearer token, Accept: application/json').")
                                                .build()
                                ))
                                .required(ImmutableList.of("url"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String url = (String) args.get("url");
                String headersStr = (String) args.getOrDefault("headers", "");

                return Single.fromCallable(() -> {
                    System.out.println(ANSI_BLUE + "[FetchURL] GET " + url + ANSI_RESET);

                    // Validate URL
                    if (url == null || url.isBlank()) {
                        return Collections.<String, Object>singletonMap("error", "URL cannot be empty.");
                    }
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        return Collections.<String, Object>singletonMap("error", "URL must start with http:// or https://");
                    }

                    try {
                        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                                .GET();

                        // Parse and apply optional headers
                        if (headersStr != null && !headersStr.isBlank()) {
                            String[] headerPairs = headersStr.split(",");
                            for (String pair : headerPairs) {
                                String trimmed = pair.trim();
                                int colonIdx = trimmed.indexOf(':');
                                if (colonIdx > 0 && colonIdx < trimmed.length() - 1) {
                                    String key = trimmed.substring(0, colonIdx).trim();
                                    String value = trimmed.substring(colonIdx + 1).trim();
                                    requestBuilder.header(key, value);
                                }
                            }
                        }

                        HttpResponse<String> response = HTTP_CLIENT.send(
                                requestBuilder.build(),
                                HttpResponse.BodyHandlers.ofString()
                        );

                        String body = response.body();
                        boolean truncated = false;

                        // Truncate if too large
                        if (body != null && body.length() > MAX_RESPONSE_BYTES) {
                            body = body.substring(0, MAX_RESPONSE_BYTES) + "\n\n...[TRUNCATED at 100KB]";
                            truncated = true;
                        }

                        StringBuilder result = new StringBuilder();
                        result.append("[HTTP ").append(response.statusCode()).append("] ");
                        result.append(url).append("\n");
                        if (truncated) {
                            result.append("[Response truncated to 100KB]\n");
                        }
                        result.append("\n").append(body != null ? body : "(empty response)");

                        return Collections.<String, Object>singletonMap("result", result.toString());

                    } catch (java.net.http.HttpConnectTimeoutException e) {
                        return Collections.<String, Object>singletonMap("error", "Connection timed out: " + url);
                    } catch (java.net.http.HttpTimeoutException e) {
                        return Collections.<String, Object>singletonMap("error", "Request timed out: " + url);
                    } catch (java.net.ConnectException e) {
                        return Collections.<String, Object>singletonMap("error", "Connection refused: " + url);
                    } catch (IllegalArgumentException e) {
                        return Collections.<String, Object>singletonMap("error", "Invalid URL: " + e.getMessage());
                    } catch (Exception e) {
                        return Collections.<String, Object>singletonMap("error", "Failed to fetch URL: " + e.getMessage());
                    }
                });
            }
        };
    }
}
