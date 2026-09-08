package com.mkpro.agents;

import com.mkpro.CentralMemory;
import com.mkpro.models.AgentConfig;
import com.mkpro.models.Provider;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the self-adaptive model resilience system (Options B + C).
 * Covers:
 * - Connection failure detection (isConnectionFailure)
 * - Alternate endpoint discovery (findAlternateOllamaEndpoint)
 * - Fallback model parsing
 * - ConfigRecommender logic
 */
public class ResilienceTest {

    private Path tempDir;
    private CentralMemory memory;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = java.nio.file.Files.createTempDirectory("resilience-test-");
        Path sharedDb = tempDir.resolve("shared").resolve("cm.db");
        Path localDb = tempDir.resolve("local").resolve("stats.db");
        memory = new CentralMemory(sharedDb, localDb);
    }

    @AfterEach
    void tearDown() {
        if (memory != null) { memory.close(); memory = null; }
        try {
            java.nio.file.Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }

    // ==========================================================================
    // Connection failure detection
    // ==========================================================================

    @Test
    void detectsConnectionRefused() {
        Exception e = new RuntimeException("Connection refused", new ConnectException("Connection refused"));
        assertTrue(isConnectionFailure(e));
    }

    @Test
    void detectsConnectTimeout() {
        Exception e = new RuntimeException("connect timed out");
        assertTrue(isConnectionFailure(e));
    }

    @Test
    void detectsUnreachable() {
        Exception e = new RuntimeException("No route to host: unreachable");
        assertTrue(isConnectionFailure(e));
    }

    @Test
    void detectsConnectionReset() {
        Exception e = new RuntimeException("Connection reset by peer");
        assertTrue(isConnectionFailure(e));
    }

    @Test
    void detectsSocketException() {
        Exception e = new RuntimeException("Socket closed unexpectedly");
        assertTrue(isConnectionFailure(e));
    }

    @Test
    void detectsTimeoutInCause() {
        Exception e = new RuntimeException("Failed", new RuntimeException("timeout waiting for response"));
        assertTrue(isConnectionFailure(e));
    }

    @Test
    void doesNotFlagModelError() {
        Exception e = new RuntimeException("model 'nonexistent' not found");
        assertFalse(isConnectionFailure(e));
    }

    @Test
    void doesNotFlagNullPointer() {
        Exception e = new NullPointerException("something was null");
        assertFalse(isConnectionFailure(e));
    }

    @Test
    void doesNotFlagGenericError() {
        Exception e = new RuntimeException("Unexpected token in JSON");
        assertFalse(isConnectionFailure(e));
    }

    @Test
    void doesNotFlagIllegalState() {
        Exception e = new IllegalStateException("Could not create LLM for Coder");
        assertFalse(isConnectionFailure(e));
    }

    // ==========================================================================
    // Alternate endpoint discovery
    // ==========================================================================

    @Test
    void findsAlternateEndpoint() {
        List<String> servers = List.of("local|http://localhost:11434", "gpu|http://10.0.0.1:11434");
        memory.saveOllamaServers(new ArrayList<>(servers));

        String alternate = findAlternateOllamaEndpoint("http://localhost:11434", memory);
        assertEquals("http://10.0.0.1:11434", alternate);
    }

    @Test
    void findsAlternateWhenFirstFails() {
        List<String> servers = List.of("local|http://localhost:11434", "gpu|http://10.0.0.1:11434", "gpu2|http://10.0.0.2:11434");
        memory.saveOllamaServers(new ArrayList<>(servers));

        String alternate = findAlternateOllamaEndpoint("http://10.0.0.1:11434", memory);
        // Should return first non-failed one
        assertEquals("http://localhost:11434", alternate);
    }

    @Test
    void returnsNullWhenOnlyOneEndpoint() {
        List<String> servers = List.of("local|http://localhost:11434");
        memory.saveOllamaServers(new ArrayList<>(servers));

        String alternate = findAlternateOllamaEndpoint("http://localhost:11434", memory);
        assertNull(alternate);
    }

    @Test
    void returnsNullWhenNoEndpoints() {
        // Empty list
        String alternate = findAlternateOllamaEndpoint("http://localhost:11434", memory);
        assertNull(alternate);
    }

    // ==========================================================================
    // Fallback model parsing
    // ==========================================================================

    @Test
    void parsePlainModel() {
        FallbackParsed parsed = parseFallbackModel("llama3.3");
        assertEquals("llama3.3", parsed.model);
        assertEquals(Provider.OLLAMA, parsed.provider);
        assertNull(parsed.serverUrl);
    }

    @Test
    void parseModelAtServer() {
        List<String> servers = List.of("gpu4090|http://10.0.0.1:11434");
        memory.saveOllamaServers(new ArrayList<>(servers));

        FallbackParsed parsed = parseFallbackModelWithMemory("codestral@gpu4090", memory);
        assertEquals("codestral", parsed.model);
        assertEquals(Provider.OLLAMA, parsed.provider);
        assertEquals("http://10.0.0.1:11434", parsed.serverUrl);
    }

    @Test
    void parseGeminiModel() {
        FallbackParsed parsed = parseFallbackModel("gemini-2.0-flash");
        assertEquals("gemini-2.0-flash", parsed.model);
        assertEquals(Provider.GEMINI, parsed.provider);
        assertNull(parsed.serverUrl);
    }

    @Test
    void parseGeminiProModel() {
        FallbackParsed parsed = parseFallbackModel("gemini-3.1-pro-preview");
        assertEquals("gemini-3.1-pro-preview", parsed.model);
        assertEquals(Provider.GEMINI, parsed.provider);
    }

    @Test
    void parseModelAtUnknownServer() {
        FallbackParsed parsed = parseFallbackModelWithMemory("codestral@nonexistent", memory);
        assertEquals("codestral", parsed.model);
        assertEquals(Provider.OLLAMA, parsed.provider);
        assertNull(parsed.serverUrl); // Can't resolve, stays null
    }

    // ==========================================================================
    // ConfigRecommender logic
    // ==========================================================================

    @Test
    void recommendationUpdatesConfig() {
        Map<String, AgentConfig> configs = new HashMap<>();
        configs.put("Coder", new AgentConfig(Provider.OLLAMA, "phi4"));

        // Simulate what applyRecommendation does
        String fallbackModel = "gemini-2.0-flash";
        Provider provider = fallbackModel.startsWith("gemini") ? Provider.GEMINI : Provider.OLLAMA;
        AgentConfig newConfig = new AgentConfig(provider, fallbackModel, null);
        configs.put("Coder", newConfig);

        assertEquals(Provider.GEMINI, configs.get("Coder").getProvider());
        assertEquals("gemini-2.0-flash", configs.get("Coder").getModelName());
    }

    @Test
    void recommendationWithServerSyntax() {
        List<String> servers = List.of("gpu4090|http://10.0.0.1:11434");
        memory.saveOllamaServers(new ArrayList<>(servers));

        Map<String, AgentConfig> configs = new HashMap<>();
        configs.put("Coder", new AgentConfig(Provider.OLLAMA, "phi4"));

        // Simulate parsing "codestral@gpu4090"
        String fallback = "codestral@gpu4090";
        String model = fallback.substring(0, fallback.indexOf('@'));
        String serverName = fallback.substring(fallback.indexOf('@') + 1);
        String serverUrl = com.mkpro.commands.impl.OllamaCommand.resolveServerUrl(serverName, memory);

        AgentConfig newConfig = new AgentConfig(Provider.OLLAMA, model, serverUrl);
        configs.put("Coder", newConfig);

        assertEquals("codestral", configs.get("Coder").getModelName());
        assertEquals("http://10.0.0.1:11434", configs.get("Coder").getServerUrl());
    }

    // ==========================================================================
    // Helpers — mirror the logic in AgentManager
    // ==========================================================================

    private boolean isConnectionFailure(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (e.getCause() != null) {
            msg += " " + (e.getCause().getMessage() != null ? e.getCause().getMessage().toLowerCase() : "");
        }
        return msg.contains("connection refused") || msg.contains("connect timed out") ||
               msg.contains("unreachable") || msg.contains("no route to host") ||
               msg.contains("connection reset") || msg.contains("econnrefused") ||
               msg.contains("socket") || msg.contains("timeout");
    }

    private String findAlternateOllamaEndpoint(String failedUrl, CentralMemory mem) {
        List<String> servers = mem.getOllamaServers();
        for (String entry : servers) {
            int sep = entry.indexOf('|');
            if (sep >= 0) {
                String url = entry.substring(sep + 1);
                if (!url.equals(failedUrl)) {
                    return url;
                }
            }
        }
        return null;
    }

    static class FallbackParsed {
        String model;
        Provider provider;
        String serverUrl;
    }

    private FallbackParsed parseFallbackModel(String fallback) {
        return parseFallbackModelWithMemory(fallback, null);
    }

    private FallbackParsed parseFallbackModelWithMemory(String fallback, CentralMemory mem) {
        FallbackParsed result = new FallbackParsed();
        result.model = fallback;
        result.provider = Provider.OLLAMA;
        result.serverUrl = null;

        if (fallback.startsWith("gemini")) {
            result.provider = Provider.GEMINI;
            return result;
        }

        if (fallback.contains("@")) {
            int atIdx = fallback.indexOf('@');
            result.model = fallback.substring(0, atIdx);
            String serverName = fallback.substring(atIdx + 1);
            if (mem != null) {
                result.serverUrl = com.mkpro.commands.impl.OllamaCommand.resolveServerUrl(serverName, mem);
            }
        }

        return result;
    }
}
