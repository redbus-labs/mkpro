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
 * AudioTranscriptionTool sends audio files to the LLM for transcription.
 * Uses Gemini's native audio understanding to:
 * - Transcribe speech to text
 * - Summarize audio content
 * - Extract key points from recordings
 * - Identify speakers (when possible)
 *
 * Supports: MP3, WAV, OGG, FLAC, M4A, WEBM
 * Requires a multimodal model (Gemini 1.5+).
 */
public class AudioTranscriptionTool {

    private static final Map<String, String> MIME_TYPES = Map.of(
        ".mp3", "audio/mpeg",
        ".wav", "audio/wav",
        ".ogg", "audio/ogg",
        ".flac", "audio/flac",
        ".m4a", "audio/mp4",
        ".webm", "audio/webm",
        ".aac", "audio/aac"
    );

    private static final long MAX_AUDIO_SIZE = 50 * 1024 * 1024; // 50MB

    /**
     * Creates the transcribe_audio tool.
     */
    public static BaseTool create() {
        return new BaseTool(
            "transcribe_audio",
            "Transcribes an audio file to text using the LLM's audio understanding capabilities. " +
            "Can also summarize, extract key points, or answer questions about audio content. " +
            "Supports: MP3, WAV, OGG, FLAC, M4A, WEBM, AAC."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                    .name(name())
                    .description(description())
                    .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(ImmutableMap.of(
                            "audio_path", Schema.builder()
                                .type("STRING")
                                .description("Path to the audio file to transcribe.")
                                .build(),
                            "task", Schema.builder()
                                .type("STRING")
                                .description("Optional: what to do with the audio. Options: " +
                                    "'transcribe' (default, full transcript), " +
                                    "'summarize' (brief summary), " +
                                    "'key_points' (extract main points), " +
                                    "or a custom question about the audio content.")
                                .build()
                        ))
                        .required(ImmutableList.of("audio_path"))
                        .build())
                    .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String audioPath = (String) args.get("audio_path");
                    String task = args.get("task") != null ? (String) args.get("task") : "transcribe";

                    try {
                        Path path = Paths.get(audioPath).toAbsolutePath();

                        // Validate file exists
                        if (!Files.exists(path)) {
                            return Collections.singletonMap("error", (Object) ("Audio file not found: " + audioPath));
                        }

                        // Validate file size
                        long size = Files.size(path);
                        if (size > MAX_AUDIO_SIZE) {
                            return Collections.singletonMap("error", (Object) ("Audio file too large: " + (size / 1024 / 1024) + "MB. Max: 50MB."));
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
                            return Collections.singletonMap("error", (Object) ("Unsupported audio format. Supported: " + MIME_TYPES.keySet()));
                        }

                        // Build prompt based on task
                        String prompt;
                        switch (task.toLowerCase()) {
                            case "summarize":
                                prompt = "Listen to this audio and provide a concise summary of its content. Include the main topic, key speakers if identifiable, and the overall message.";
                                break;
                            case "key_points":
                                prompt = "Listen to this audio and extract the key points as a bullet list. Focus on decisions, action items, and important information.";
                                break;
                            case "transcribe":
                                prompt = "Transcribe this audio file to text. Provide a complete, accurate transcription. If there are multiple speakers, indicate speaker changes.";
                                break;
                            default:
                                // Custom question about the audio
                                prompt = task;
                                break;
                        }

                        // Read audio bytes
                        byte[] audioBytes = Files.readAllBytes(path);
                        System.out.println(ANSI_BLUE + "[Audio] Processing: " + fileName +
                            " (" + (audioBytes.length / 1024) + "KB, " + mimeType + ")" + ANSI_RESET);

                        // Build multimodal content with audio + text prompt
                        Part audioPart = Part.builder()
                            .inlineData(Blob.builder()
                                .mimeType(mimeType)
                                .data(audioBytes)
                                .build())
                            .build();
                        Part textPart = Part.fromText(prompt);

                        Content content = Content.builder()
                            .role("user")
                            .parts(List.of(textPart, audioPart))
                            .build();

                        // Get API key for Gemini
                        String apiKey = System.getProperty("GOOGLE_API_KEY",
                            System.getenv("GOOGLE_API_KEY"));

                        if (apiKey == null || apiKey.isEmpty()) {
                            return Map.of(
                                "status", "no_api_key",
                                "message", "Audio transcription requires GOOGLE_API_KEY. Audio loaded successfully.",
                                "file", fileName,
                                "size_kb", audioBytes.length / 1024,
                                "mime_type", mimeType,
                                "task", task
                            );
                        }

                        // Call Gemini REST API for audio
                        String base64Audio = java.util.Base64.getEncoder().encodeToString(audioBytes);
                        String requestBody = String.format(
                            "{\"contents\":[{\"parts\":[{\"text\":\"%s\"},{\"inline_data\":{\"mime_type\":\"%s\",\"data\":\"%s\"}}]}]}",
                            prompt.replace("\"", "\\\"").replace("\n", "\\n"),
                            mimeType,
                            base64Audio
                        );

                        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                            .connectTimeout(java.time.Duration.ofSeconds(30)).build();

                        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=" + apiKey))
                            .header("Content-Type", "application/json")
                            .timeout(java.time.Duration.ofSeconds(120))
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody))
                            .build();

                        java.net.http.HttpResponse<String> httpResponse = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

                        String result = "";
                        if (httpResponse.statusCode() == 200) {
                            String body = httpResponse.body();
                            int textStart = body.indexOf("\"text\":");
                            if (textStart >= 0) {
                                int valueStart = body.indexOf("\"", textStart + 7) + 1;
                                int valueEnd = body.indexOf("\"", valueStart);
                                if (valueEnd > valueStart) {
                                    result = body.substring(valueStart, valueEnd)
                                        .replace("\\n", "\n").replace("\\\"", "\"");
                                }
                            }
                            if (result.isEmpty()) {
                                result = body;
                            }
                        } else {
                            return Map.of("error", "Gemini API error (" + httpResponse.statusCode() + "): " + httpResponse.body());
                        }

                        return Map.of(
                            "transcription", result,
                            "file", fileName,
                            "size_kb", audioBytes.length / 1024,
                            "task", task,
                            "mime_type", mimeType
                        );

                    } catch (Exception e) {
                        return Collections.singletonMap("error", (Object) ("Audio transcription failed: " + e.getMessage()));
                    }
                });
            }
        };
    }
}
