package com.mkpro.knowledge;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple HTTP fetcher for knowledge sources.
 * Thread-safe, reusable instance with graceful error handling.
 */
public class SourceFetcher {

    private static final String USER_AGENT = "mkpro-knowledge/1.0";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_RESPONSE_SIZE = 500 * 1024; // 500KB

    private final HttpClient httpClient;

    public SourceFetcher() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Fetches the content at the given URL via HTTP GET.
     * Returns the response body as a String, or an error message if the request fails.
     *
     * @param url the URL to fetch
     * @return response body or error message string
     */
    public String fetch(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .timeout(READ_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                return "[FETCH ERROR: " + url + " - HTTP " + statusCode + "]";
            }

            String body = response.body();
            if (body != null && body.length() > MAX_RESPONSE_SIZE) {
                return body.substring(0, MAX_RESPONSE_SIZE)
                        + "\n\n[TRUNCATED: Response exceeded 500KB limit. Original size: "
                        + body.length() + " bytes]";
            }

            return body != null ? body : "";
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return "[FETCH ERROR: " + url + " - " + reason + "]";
        }
    }

    /**
     * Fetches all given URLs and returns a map of url → content.
     *
     * @param urls list of URLs to fetch
     * @return map of URL to fetched content (or error message)
     */
    public Map<String, String> fetchAll(List<String> urls) {
        Map<String, String> results = new LinkedHashMap<>();
        if (urls == null) {
            return results;
        }
        for (String url : urls) {
            results.put(url, fetch(url));
        }
        return results;
    }
}
