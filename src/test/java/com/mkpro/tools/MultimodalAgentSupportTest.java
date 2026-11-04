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

    @Test
    void testCoordinatorHasVisionAndAudioTools() throws Exception {
        com.mkpro.agents.AgentManager am = new com.mkpro.agents.AgentManager(null, null, null, "test-key", "http://localhost:11434", null, memory, com.mkpro.models.RunnerType.IN_MEMORY, java.nio.file.Paths.get("teams/default.yaml"), null, null);
        java.util.List<String> coordToolNames = am.getCoordinatorTools().stream().map(com.google.adk.tools.BaseTool::name).toList();
        assertTrue(coordToolNames.contains("analyze_image"), "Coordinator must have analyze_image tool");
        assertTrue(coordToolNames.contains("transcribe_audio"), "Coordinator must have transcribe_audio tool");
        com.mkpro.models.AgentDefinition coordDef = am.getAgentDefinitions().get("Coordinator");
        assertTrue(coordDef.getInstruction().contains("analyze_image") && coordDef.getInstruction().contains("transcribe_audio"));
        for (String ag : java.util.List.of("Coder", "SysAdmin", "Tester", "DocWriter", "SecurityAuditor", "Architect", "DataAnalyst", "AndroidDev", "IosDev", "ProductManager")) {
            com.mkpro.models.AgentDefinition def = am.getAgentDefinitions().get(ag);
            assertTrue(def.getTools().contains("vision"), ag + " should have vision tool");
            assertTrue(def.getTools().contains("audio"), ag + " should have audio tool");
        }
    }

    @Test
    void testDirectDiskFileReadVisionAnalysis() throws Exception {
        com.mkpro.security.PathValidator.initialize(tempDir, null);
        Path img = tempDir.resolve("bus.png");
        Files.write(img, new byte[]{1, 2, 3, 4});
        String vllmUrl = "http://localhost:" + vllmServer.getAddress().getPort() + "/v1";
        memory.saveAgentConfig("Coordinator", new AgentConfig(Provider.OPENAI, "gemma-4-test", vllmUrl));
        com.mkpro.agents.AgentManager.currentTurnMediaParts.clear();

        Map<String, Object> result = FileSystemTools.create().runAsync(Map.of("path", img.toString()), null).blockingGet();

        assertTrue(result.get("content").toString().contains("Mock vLLM response"));
        assertTrue(result.get("analysis").toString().contains("Mock vLLM response"));
        assertTrue(!com.mkpro.agents.AgentManager.currentTurnMediaParts.isEmpty());
        assertTrue(capturedRequests.get("vllm").contains("image_url"));
    }

    @Test
    void testAutoAttachLocalDiskMediaInTerminalUiAndWebChatServer() throws Exception {
        Path img = tempDir.resolve("screenshot.png");
        Files.write(img, new byte[]{9, 8, 7});
        com.google.genai.types.Content cliContent = com.mkpro.ui.TerminalUI.buildUserMessageContent("Check " + img.toString());
        assertTrue(cliContent.parts().orElse(java.util.List.of()).size() >= 2);
        assertTrue(!com.mkpro.agents.AgentManager.currentTurnMediaParts.isEmpty());

        com.google.genai.types.Content webContent = com.mkpro.web.WebChatServer.buildUserMessageContent("Check " + img.toString(), null);
        assertTrue(webContent.parts().orElse(java.util.List.of()).size() >= 2);
        assertTrue(!com.mkpro.agents.AgentManager.currentTurnMediaParts.isEmpty());
    }
}
