package com.mkpro.tools;

import com.mkpro.CentralMemory;
import com.mkpro.models.AgentConfig;
import com.mkpro.models.Provider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class MultimodalAgentSupportTest {

    private HttpServer vllmServer;
    private HttpServer ollamaServer;
    private final Map<String, String> capturedRequests = new ConcurrentHashMap<>();
    private CentralMemory memory;
    private Path tempDir;

    @BeforeEach
    void setup() throws IOException {
        tempDir = Files.createTempDirectory("mkpro-multimodal-test");
        memory = new CentralMemory(tempDir.resolve("shared.db"), tempDir.resolve("local.db"));

        vllmServer = HttpServer.create(new InetSocketAddress(0), 0);
        vllmServer.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            capturedRequests.put("vllm", body);
            String response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Mock vLLM response\"}}]}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) { os.write(response.getBytes()); }
        });
        vllmServer.start();

        ollamaServer = HttpServer.create(new InetSocketAddress(0), 0);
        ollamaServer.createContext("/api/chat", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            capturedRequests.put("ollama", body);
            String response = "{\"message\":{\"role\":\"assistant\",\"content\":\"Mock Ollama response\"},\"done\":true}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) { os.write(response.getBytes()); }
        });
        ollamaServer.start();
    }

    @AfterEach
    void tearDown() {
        vllmServer.stop(0);
        ollamaServer.stop(0);
        memory.close();
    }

    @Test
    void testVisionToolWithVllmOpenAiProvider() throws Exception {
        Path img = tempDir.resolve("test.png");
        Files.write(img, new byte[]{1, 2, 3});
        String vllmUrl = "http://localhost:" + vllmServer.getAddress().getPort() + "/v1";
        memory.saveAgentConfig("Coordinator", new AgentConfig(Provider.OPENAI, "gemma-4-test", vllmUrl));

        Map<String, Object> result = VisionTool.create().runAsync(Map.of("image_path", img.toString()), null).blockingGet();

        assertTrue(result.get("analysis").toString().contains("Mock vLLM response"));
        assertTrue(capturedRequests.get("vllm").contains("image_url"));
        assertTrue(capturedRequests.get("vllm").contains("data:image/png;base64,"));
    }

    @Test
    void testAudioTranscriptionToolWithVllmOpenAiProvider() throws Exception {
        Path audio = tempDir.resolve("test.wav");
        Files.write(audio, new byte[]{1, 2, 3});
        String vllmUrl = "http://localhost:" + vllmServer.getAddress().getPort() + "/v1";
        memory.saveAgentConfig("Coordinator", new AgentConfig(Provider.OPENAI, "gemma-4-test", vllmUrl));

        Map<String, Object> result = AudioTranscriptionTool.create().runAsync(Map.of("audio_path", audio.toString()), null).blockingGet();

        assertTrue(result.get("transcription").toString().contains("Mock vLLM response"));
        assertTrue(capturedRequests.get("vllm").contains("input_audio"));
        assertTrue(capturedRequests.get("vllm").contains("\"format\":\"wav\""));
    }

    @Test
    void testVisionToolWithOllamaProvider() throws Exception {
        Path img = tempDir.resolve("test.png");
        Files.write(img, new byte[]{1, 2, 3});
        String ollamaUrl = "http://localhost:" + ollamaServer.getAddress().getPort();
        memory.saveAgentConfig("Coordinator", new AgentConfig(Provider.OLLAMA, "llava-test", ollamaUrl));

        Map<String, Object> result = VisionTool.create().runAsync(Map.of("image_path", img.toString()), null).blockingGet();

        assertTrue(result.get("analysis").toString().contains("Mock Ollama response"));
        assertTrue(capturedRequests.get("ollama").contains("\"images\":["));
    }

    @Test
    void testAudioTranscriptionToolWithOllamaProvider() throws Exception {
        Path audio = tempDir.resolve("test.wav");
        Files.write(audio, new byte[]{1, 2, 3});
        String ollamaUrl = "http://localhost:" + ollamaServer.getAddress().getPort();
        memory.saveAgentConfig("Coordinator", new AgentConfig(Provider.OLLAMA, "llava-test", ollamaUrl));

        Map<String, Object> result = AudioTranscriptionTool.create().runAsync(Map.of("audio_path", audio.toString()), null).blockingGet();

        assertTrue(result.get("transcription").toString().contains("Mock Ollama response"));
        assertTrue(capturedRequests.get("ollama").contains("\"images\":["));
    }
}