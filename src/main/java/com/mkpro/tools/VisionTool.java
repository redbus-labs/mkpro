package com.mkpro.tools;

import static com.mkpro.ui.AnsiColors.*;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.*;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Single;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * VisionTool sends images to the configured LLM (Gemini) for visual analysis.
 * Agents can use this to:
 * - Describe UI screenshots
 * - Analyze architecture diagrams
 * - Read text from images (OCR)
 * - Compare design mockups with implementations
 * - Understand error screenshots
 *
 * Requires a vision-capable model (Gemini 1.5+, GPT-4o via Azure).
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

    private static final long MAX_IMAGE_SIZE = 20 * 1024 * 1024; // 20MB

    /**
     * Creates the analyze_image tool.
     */
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
                        Part textPart = Part.fromText(prompt);

                        Content content = Content.builder()
                            .role("user")
                            .parts(List.of(textPart, imagePart))
                            .build();

                        // Get API key from system for Gemini vision call
                        String apiKey = System.getProperty("GOOGLE_API_KEY", 
                            System.getenv("GOOGLE_API_KEY"));
                        
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

                    } catch (Exception e) {
                        return Collections.singletonMap("error", (Object) ("Vision analysis failed: " + e.getMessage()));
                    }
                });
            }
        };
    }
}
