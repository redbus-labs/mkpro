package com.mkpro.tools;

import static com.mkpro.ui.AnsiColors.*;

import com.google.adk.models.LlmRequest;
import com.google.adk.models.OpenAiBaseLM;
import com.google.adk.models.OllamaBaseLM;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.*;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.mkpro.CentralMemory;
import com.mkpro.commands.impl.VllmCommand;
import com.mkpro.config.ConfigService;
import com.mkpro.models.AgentConfig;
import com.mkpro.models.Provider;
import io.reactivex.rxjava3.core.Single;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * VisionTool sends images to the configured LLM for visual analysis.
 * Agents can use this to:
 * - Describe UI screenshots
 * - Analyze architecture diagrams
 * - Read text from images (OCR)
 * - Compare design mockups with implementations
 * - Understand error screenshots
 *
 * Requires a vision-capable model (Gemini 1.5+, GPT-4o / vLLM, Ollama vision models).
 * The image is sent as inline_data and never leaves the LLM API call.
 */
public class VisionTool {

    private static final Map<String, String> MIME_TYPES = Map.of(
        ".jpg", "image/jpeg",
        ".jpeg", "image/jpeg",
        ".png", "image/png",
        ".webp", "image/webp",
        ".gif", "image/gif",
        ".bmp", "image/bmp"
    );

    private static final long MAX_IMAGE_SIZE = 20 * 1024 * 1024;
    private static final long MAX_MEDIA_SIZE = 50 * 1024 * 1024;

    private static final Map<String, String> MEDIA_MIME_TYPES = Map.ofEntries(
        Map.entry(".jpg", "image/jpeg"),
        Map.entry(".jpeg", "image/jpeg"),
        Map.entry(".png", "image/png"),
        Map.entry(".webp", "image/webp"),
        Map.entry(".gif", "image/gif"),
        Map.entry(".bmp", "image/bmp"),
        Map.entry(".mp3", "audio/mpeg"),
        Map.entry(".wav", "audio/wav"),
        Map.entry(".ogg", "audio/ogg"),
        Map.entry(".flac", "audio/flac"),
        Map.entry(".m4a", "audio/mp4"),
        Map.entry(".webm", "audio/webm"),
        Map.entry(".aac", "audio/aac")
    );

    public static String analyzeImageFile(Path path, String prompt) {
        if (path == null) return "Image path cannot be null.";
        java.util.Map<String, Object> args = new java.util.HashMap<>();
        args.put("image_path", path.toAbsolutePath().toString());
        if (prompt != null && !prompt.isBlank()) args.put("prompt", prompt);
        Map<String, Object> res = create().runAsync(args, null).blockingGet();
        if (res.containsKey("analysis")) return String.valueOf(res.get("analysis"));
        if (res.containsKey("message")) return String.valueOf(res.get("message"));
        if (res.containsKey("error")) return String.valueOf(res.get("error"));
        return res.toString();
    }
 // 20MB

    /**
     * Creates the analyze_image tool.
     */
    public static String getMediaMimeType(String fileNameOrPath) {
        if (fileNameOrPath == null) return null;
        String lower = fileNameOrPath.toLowerCase();
        for (Map.Entry<String, String> entry : MEDIA_MIME_TYPES.entrySet()) {
            if (lower.endsWith(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    public static List<Path> extractLocalMediaPaths(String text) {
        if (text == null || text.isBlank()) return Collections.emptyList();
        java.util.Set<Path> found = new java.util.LinkedHashSet<>();
        char[] quotes = new char[]{34, 39, 96};
        for (int qi = 0; qi < quotes.length; qi++) {
            char q = quotes[qi];
            int idx = 0;
            while ((idx = text.indexOf(q, idx)) >= 0) {
                int end = text.indexOf(q, idx + 1);
                if (end > idx + 1) {
                    resolveCandidateMediaPath(text.substring(idx + 1, end), found);
                    idx = end + 1;
                } else break;
            }
        }
        for (String tok : text.split("\\s+")) {
            resolveCandidateMediaPath(tok, found);
            int eq = tok.indexOf("=");
            if (eq >= 0 && eq + 1 < tok.length()) resolveCandidateMediaPath(tok.substring(eq + 1), found);
        }
        return new java.util.ArrayList<>(found);
    }

    private static void resolveCandidateMediaPath(String raw, java.util.Set<Path> target) {
        if (raw == null) return;
        String c = raw.trim();
        while (!c.isEmpty() && ("([{" + (char)60 + (char)59 + (char)34 + (char)39 + "`,").indexOf(c.charAt(0)) >= 0) c = c.substring(1).trim();
        while (!c.isEmpty() && (")]}" + (char)62 + (char)59 + (char)34 + (char)39 + "`,.?!").indexOf(c.charAt(c.length() - 1)) >= 0) c = c.substring(0, c.length() - 1).trim();
        if (c.isEmpty()) return;
        String lower = c.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("data:")) return;
        if (getMediaMimeType(lower) == null) return;
        try {
            Path p = Paths.get(c);
            if (!p.isAbsolute()) p = Paths.get("").toAbsolutePath().resolve(p);
            p = p.toAbsolutePath().normalize();
            if (Files.exists(p) && Files.isRegularFile(p) && Files.size(p) <= MAX_MEDIA_SIZE) target.add(p);
        } catch (Exception ignored) {}
    }

    public static List<Part> extractLocalMediaParts(String text) {
        List<Path> paths = extractLocalMediaPaths(text);
        if (paths.isEmpty()) return Collections.emptyList();
        List<Part> parts = new java.util.ArrayList<>();
        for (Path p : paths) {
            try {
                String mime = getMediaMimeType(p.getFileName().toString());
                if (mime != null) {
                    byte[] bytes = Files.readAllBytes(p);
                    parts.add(Part.builder().inlineData(Blob.builder().mimeType(mime).data(bytes).build()).build());
                }
            } catch (Exception ignored) {}
        }
        return parts;
    }

    public static List<com.mkpro.web.WebChatServer.ImageAttachment> extractLocalMediaAttachments(String text) {
        List<Path> paths = extractLocalMediaPaths(text);
        if (paths.isEmpty()) return Collections.emptyList();
        List<com.mkpro.web.WebChatServer.ImageAttachment> list = new java.util.ArrayList<>();
        for (Path p : paths) {
            try {
                String name = p.getFileName().toString();
                String mime = getMediaMimeType(name);
                if (mime != null) {
                    byte[] bytes = Files.readAllBytes(p);
                    list.add(new com.mkpro.web.WebChatServer.ImageAttachment(name, mime, bytes));
                }
            } catch (Exception ignored) {}
        }
        return list;
    }

    public static BaseTool create() {
        return new BaseTool(
            "analyze_image",
            "Analyzes an image using the LLM's vision capabilities. " +
            "Send an image file path and an optional prompt describing what to analyze. " +
            "The model will describe the image content, read text, identify UI elements, " +
            "or answer specific questions about the image. " +
            "Supports: JPG, PNG, WebP, GIF, BMP."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                    .name(name())
                    .description(description())
                    .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(ImmutableMap.of(
                            "image_path", Schema.builder()
                                .type("STRING")
                                .description("Path to the image file to analyze.")
                                .build(),
                            "prompt", Schema.builder()
                                .type("STRING")
                                .description("Optional: specific question or instruction about the image " +
                                    "(e.g., 'Describe the UI layout', 'What text is in this screenshot?', " +
                                    "'Compare this with the design spec'). " +
                                    "Defaults to a general description if not provided.")
                                .build()
                        ))
                        .required(ImmutableList.of("image_path"))
                        .build())
                    .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String imagePath = (String) args.get("image_path");
                    String prompt = args.get("prompt") != null ? (String) args.get("prompt") :
                        "Describe this image in detail. If it's a UI/screenshot, describe the layout, elements, and any visible text. If it's a diagram, explain the architecture or flow shown.";

                    try {
                        Path path = Paths.get(imagePath).toAbsolutePath();

                        // Validate file exists
                        if (!Files.exists(path)) {
                            return Collections.singletonMap("error", (Object) ("Image file not found: " + imagePath));
                        }

                        // Validate file size
                        long size = Files.size(path);
                        if (size > MAX_IMAGE_SIZE) {
                            return Collections.singletonMap("error", (Object) ("Image too large: " + (size / 1024 / 1024) + "MB. Max: 20MB."));
                        }

                        // Determine MIME type
                        String fileName = path.getFileName().toString().toLowerCase();
                        String mimeType = null;
                        for (Map.Entry<String, String> entry : MIME_TYPES.entrySet()) {
                            if (fileName.endsWith(entry.getKey())) {
                                mimeType = entry.getValue();
                                break;
                            }
                        }
                        if (mimeType == null) {
                            return Collections.singletonMap("error", (Object) ("Unsupported image format. Supported: " + MIME_TYPES.keySet()));
                        }

                        // Read image bytes
                        byte[] imageBytes = Files.readAllBytes(path);
                        System.out.println(ANSI_BLUE + "[Vision] Analyzing image: " + fileName +
                            " (" + (imageBytes.length / 1024) + "KB)" + ANSI_RESET);

                        // Build multimodal content with image + text prompt
                        Part imagePart = Part.builder()
                            .inlineData(Blob.builder()
                                .mimeType(mimeType)
                                .data(imageBytes)
                                .build())
                            .build();
                        if (com.mkpro.agents.AgentManager.currentTurnMediaParts != null) { com.mkpro.agents.AgentManager.currentTurnMediaParts.add(imagePart); }
                        Part textPart = Part.fromText(prompt);

                        Content content = Content.builder()
                            .role("user")
                            .parts(List.of(textPart, imagePart))
                            .build();

                        CentralMemory cm = CentralMemory.getInstance();
                        AgentConfig agentConfig = Optional.ofNullable(cm.getAgentConfig("Coordinator")).orElse(cm.getAgentConfig("default"));

                        if (agentConfig != null && agentConfig.getProvider() == Provider.OPENAI) {
                            String openAiUrl = agentConfig.hasServerUrl() ? agentConfig.getServerUrl() : VllmCommand.resolveServerUrl("default", cm);
                            if (openAiUrl == null) openAiUrl = new ConfigService().getSetting("openai.base.url", "http://localhost:8000/v1");
                            String openAiKey = VllmCommand.resolveServerApiKey(openAiUrl, cm);
                            if (openAiKey == null) openAiKey = new ConfigService().getSetting("openai.api.key", System.getenv("OPENAI_API_KEY"));
                            int maxContext = VllmCommand.resolveServerMaxContext(openAiUrl, cm);

                            OpenAiBaseLM llm = new OpenAiBaseLM(agentConfig.getModelName(), openAiUrl, openAiKey != null ? openAiKey : "").withMaxContextTokens(maxContext).withTruncation(maxContext, "left");
                            LlmRequest llmReq = LlmRequest.builder()
                                .model(agentConfig.getModelName())
                                .contents(List.of(content))
                                .config(GenerateContentConfig.builder().build())
                                .build();

                            StringBuilder sb = new StringBuilder();
                            for (LlmResponse resp : llm.generateContent(llmReq, false).blockingIterable()) {
                                resp.content().flatMap(Content::parts).ifPresent(parts -> {
                                    for (Part p : parts) {
                                        p.text().ifPresent(sb::append);
                                    }
                                });
                            }
                            return Map.of("analysis", sb.toString().trim(), "file", fileName, "size_kb", imageBytes.length / 1024, "mime_type", mimeType, "provider", "OPENAI", "model", agentConfig.getModelName());
                        } else if (agentConfig != null && agentConfig.getProvider() == Provider.OLLAMA) {
                            String ollamaUrl = agentConfig.hasServerUrl() ? agentConfig.getServerUrl() : new ConfigService().getSetting(ConfigService.PROP_OLLAMA_URL, "http://localhost:11434");
                            OllamaBaseLM llm = new OllamaBaseLM(agentConfig.getModelName(), ollamaUrl);
                            LlmRequest llmReq = LlmRequest.builder()
                                .model(agentConfig.getModelName())
                                .contents(List.of(content))
                                .config(GenerateContentConfig.builder().build())
                                .build();

                            StringBuilder sb = new StringBuilder();
                            for (LlmResponse resp : llm.generateContent(llmReq, false).blockingIterable()) {
                                resp.content().flatMap(Content::parts).ifPresent(parts -> {
                                    for (Part p : parts) {
                                        p.text().ifPresent(sb::append);
                                    }
                                });
                            }
                            return Map.of("analysis", sb.toString().trim(), "file", fileName, "size_kb", imageBytes.length / 1024, "mime_type", mimeType, "provider", "OLLAMA", "model", agentConfig.getModelName());
                        } else {
                            // Get API key from system / config for Gemini vision call
                            String apiKey = System.getProperty("GOOGLE_API_KEY", System.getenv("GOOGLE_API_KEY"));
                            if (apiKey == null || apiKey.isEmpty()) {
                                apiKey = System.getProperty("GEMINI_API_KEY", System.getenv("GEMINI_API_KEY"));
                            }
                            if (apiKey == null || apiKey.isEmpty()) {
                                apiKey = new ConfigService().getSetting(ConfigService.PROP_GEMINI_KEY, null);
                            }

                            if (apiKey == null || apiKey.isEmpty()) {
                                // Fall back: return image metadata without AI analysis
                                return Map.of(
                                    "status", "no_api_key",
                                    "message", "Vision analysis requires GOOGLE_API_KEY. Image loaded successfully.",
                                    "file", fileName,
                                    "size_kb", imageBytes.length / 1024,
                                    "mime_type", mimeType,
                                    "prompt_used", prompt
                                );
                            }

                            // Call Gemini REST API for vision
                            String base64Image = java.util.Base64.getEncoder().encodeToString(imageBytes);
                            String requestBody = String.format(
                                "{\"contents\":[{\"parts\":[{\"text\":\"%s\"},{\"inline_data\":{\"mime_type\":\"%s\",\"data\":\"%s\"}}]}]}",
                                prompt.replace("\"", "\\\"").replace("\n", "\\n"),
                                mimeType,
                                base64Image
                            );

                            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                                .connectTimeout(java.time.Duration.ofSeconds(30)).build();

                            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=" + apiKey))
                                .header("Content-Type", "application/json")
                                .timeout(java.time.Duration.ofSeconds(60))
                                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody))
                                .build();

                            java.net.http.HttpResponse<String> httpResponse = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

                            String analysis = "";
                            if (httpResponse.statusCode() == 200) {
                                // Parse response — extract text from candidates[0].content.parts[0].text
                                String body = httpResponse.body();
                                int textStart = body.indexOf("\"text\":");
                                if (textStart >= 0) {
                                    int valueStart = body.indexOf("\"", textStart + 7) + 1;
                                    int valueEnd = body.indexOf("\"", valueStart);
                                    if (valueEnd > valueStart) {
                                        analysis = body.substring(valueStart, valueEnd)
                                            .replace("\\n", "\n").replace("\\\"", "\"");
                                    }
                                }
                                if (analysis.isEmpty()) {
                                    analysis = body; // Return raw response if parsing fails
                                }
                            } else {
                                return Map.of("error", "Gemini API error (" + httpResponse.statusCode() + "): " + httpResponse.body());
                            }

                            return Map.of(
                                "analysis", analysis,
                                "file", fileName,
                                "size_kb", imageBytes.length / 1024,
                                "mime_type", mimeType
                            );
                        }
                    } catch (Exception e) {
                        return Collections.singletonMap("error", (Object) ("Vision analysis failed: " + e.getMessage()));
                    }
                });
            }
        };
    }
}
