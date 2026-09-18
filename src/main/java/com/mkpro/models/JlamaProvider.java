package com.mkpro.models;

import com.github.tjake.jlama.model.AbstractModel;
import com.github.tjake.jlama.model.ModelSupport;
import com.github.tjake.jlama.safetensors.DType;
import com.github.tjake.jlama.safetensors.prompt.PromptContext;
import com.github.tjake.jlama.model.functions.Generator;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.GenericLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import io.reactivex.rxjava3.core.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JlamaProvider: Pure Java LLM inference via Jlama.
 * Extends Google ADK's BaseLlm so it plugs directly into AgentManager.
 * 
 * Usage:
 *   BaseLlm llm = new JlamaProvider("tjake/Llama-3.2-1B-Instruct-JQ4", modelsDir);
 *   // Use like any other ADK BaseLlm
 */
public class JlamaProvider extends BaseLlm {

    private static final Logger logger = LoggerFactory.getLogger(JlamaProvider.class);
    private static final int DEFAULT_MAX_TOKENS = 2048;
    private static final float DEFAULT_TEMPERATURE = 0.7f;

    private final String modelsDirectory;
    private final AbstractModel loadedModel;
    private final AtomicInteger totalInputTokens = new AtomicInteger(0);
    private final AtomicInteger totalOutputTokens = new AtomicInteger(0);

    // Shared model cache — avoids reloading same model for multiple agents
    private static final Map<String, AbstractModel> MODEL_CACHE = new ConcurrentHashMap<>();

    /**
     * Create a JlamaProvider for a specific model.
     * @param modelName HuggingFace model name (e.g., "tjake/Llama-3.2-1B-Instruct-JQ4")
     * @param modelsDirectory Local directory where models are stored
     */
    public JlamaProvider(String modelName, String modelsDirectory) {
        super(modelName);
        this.modelsDirectory = modelsDirectory;

        // Load model (from cache if available)
        this.loadedModel = MODEL_CACHE.computeIfAbsent(modelName, name -> {
            // Try flat format first (how Jlama Downloader stores: owner_name)
            File modelPath = new File(modelsDirectory, name.replace('/', '_'));
            if (!modelPath.exists()) {
                // Try nested format: owner/name
                modelPath = new File(modelsDirectory, name.replace('/', File.separatorChar));
            }
            if (!modelPath.exists()) {
                throw new IllegalStateException(
                    "Model not found: " + name +
                    ". Download it first with /jlama download " + name);
            }
            logger.info("[Jlama] Loading model: {} from {}", name, modelPath);
            return ModelSupport.loadModel(modelPath, DType.F32, DType.I8);
        });
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest request, boolean streaming) {
        // Always use streaming internally — ADK subscribes reactively and expects partial emissions
        return generateStreaming(request);
    }

    @Override
    public BaseLlmConnection connect(LlmRequest request) {
        return new GenericLlmConnection(this, request);
    }

    /**
     * Generate a streaming response (token-by-token).
     */
    private Flowable<LlmResponse> generateStreaming(LlmRequest request) {
        return Flowable.create(emitter -> {
            try {
                PromptContext ctx = buildPromptContext(request);
                int maxTokens = extractMaxTokens(request);
                float temperature = extractTemperature(request);

                StringBuilder fullResponse = new StringBuilder();
                AtomicInteger tokenCount = new AtomicInteger(0);

                Generator.Response response = loadedModel.generate(
                    UUID.randomUUID(), ctx, temperature, maxTokens, (token, timing) -> {
                        if (token != null && !token.isEmpty() && !emitter.isCancelled()) {
                            fullResponse.append(token);
                            tokenCount.incrementAndGet();

                            Content partialContent = Content.fromParts(Part.fromText(token));
                            LlmResponse partialResponse = LlmResponse.builder()
                                .content(partialContent)
                                .partial(true)
                                .turnComplete(false)
                                .build();
                            emitter.onNext(partialResponse);
                        }
                    });

                // Final turn-complete signal
                int inputTokens = response.promptTokens;
                int outputTokens = response.generatedTokens;
                totalInputTokens.addAndGet(inputTokens);
                totalOutputTokens.addAndGet(outputTokens);

                LlmResponse finalResponse = LlmResponse.builder()
                    .content(Content.fromParts(Part.fromText("")))
                    .turnComplete(true)
                    .partial(false)
                    .usageMetadata(GenerateContentResponseUsageMetadata.builder()
                        .promptTokenCount(inputTokens)
                        .candidatesTokenCount(outputTokens)
                        .totalTokenCount(inputTokens + outputTokens)
                        .build())
                    .build();
                emitter.onNext(finalResponse);
                emitter.onComplete();
            } catch (Exception e) {
                if (!emitter.isCancelled()) {
                    emitter.onError(e);
                }
            }
        }, io.reactivex.rxjava3.core.BackpressureStrategy.BUFFER);
    }

    /**
     * Build PromptContext from ADK LlmRequest (system instructions + content history).
     * Automatically truncates if prompt exceeds model's context window.
     */
    private PromptContext buildPromptContext(LlmRequest request) {
        // Extract system instruction
        String systemPrompt = request.getFirstSystemInstruction().orElse("");

        // Extract conversation messages
        List<Content> contents = request.contents();
        StringBuilder userMessage = new StringBuilder();

        if (contents != null) {
            for (Content content : contents) {
                if (content.parts().isPresent()) {
                    for (Part part : content.parts().get()) {
                        if (part.text().isPresent()) {
                            userMessage.append(part.text().get()).append("\n");
                        }
                    }
                }
            }
        }

        // Get context window size and reserve space for output
        int contextLength = loadedModel.getConfig().contextLength;
        int maxPromptTokens = contextLength - Math.min(DEFAULT_MAX_TOKENS, contextLength / 4);

        // Estimate tokens (~4 chars per token) and truncate if needed
        int estimatedTokens = (systemPrompt.length() + userMessage.length()) / 4;
        if (estimatedTokens > maxPromptTokens) {
            logger.info("[Jlama] Prompt too large ({} est. tokens, max {}). Truncating.", estimatedTokens, maxPromptTokens);
            int maxChars = maxPromptTokens * 4;

            // Prioritize user message over system prompt for small models
            if (userMessage.length() > maxChars / 2) {
                // Truncate system prompt aggressively, keep user message
                int sysMax = Math.min(systemPrompt.length(), maxChars / 4);
                systemPrompt = systemPrompt.substring(0, sysMax) + "\n[System prompt truncated for context limit]";
                int userMax = maxChars - systemPrompt.length();
                if (userMessage.length() > userMax) {
                    userMessage = new StringBuilder(userMessage.substring(userMessage.length() - userMax));
                }
            } else {
                // Truncate system prompt to fit
                int sysMax = maxChars - userMessage.length();
                if (systemPrompt.length() > sysMax) {
                    systemPrompt = systemPrompt.substring(0, sysMax) + "\n[System prompt truncated for context limit]";
                }
            }
        }

        // Use model's prompt support if available
        if (loadedModel.promptSupport().isPresent()) {
            var builder = loadedModel.promptSupport().get().builder();
            if (!systemPrompt.isEmpty()) {
                builder.addSystemMessage(systemPrompt);
            }
            if (userMessage.length() > 0) {
                builder.addUserMessage(userMessage.toString().trim());
            }
            return builder.build();
        }

        // Fallback: concatenate system + user
        String fullPrompt = systemPrompt.isEmpty() 
            ? userMessage.toString().trim() 
            : systemPrompt + "\n\n" + userMessage.toString().trim();
        return PromptContext.of(fullPrompt);
    }

    private int extractMaxTokens(LlmRequest request) {
        if (request.config().isPresent()) {
            var config = request.config().get();
            if (config.maxOutputTokens().isPresent()) {
                return config.maxOutputTokens().get();
            }
        }
        return DEFAULT_MAX_TOKENS;
    }

    private float extractTemperature(LlmRequest request) {
        if (request.config().isPresent()) {
            var config = request.config().get();
            if (config.temperature().isPresent()) {
                return config.temperature().get().floatValue();
            }
        }
        return DEFAULT_TEMPERATURE;
    }

    // ═══ Stats & Management ═══

    public int getTotalInputTokens() { return totalInputTokens.get(); }
    public int getTotalOutputTokens() { return totalOutputTokens.get(); }
    public String getModelsDirectory() { return modelsDirectory; }
    public boolean isModelLoaded() { return loadedModel != null; }

    /**
     * Evict a model from the shared cache (e.g., when removing a model).
     */
    public static void evictFromCache(String modelName) {
        MODEL_CACHE.remove(modelName);
    }

    /**
     * Check if a model exists locally (downloaded).
     */
    public static boolean isModelDownloaded(String modelName, String modelsDirectory) {
        File modelPath = new File(modelsDirectory, modelName.replace('/', File.separatorChar));
        return modelPath.exists() && modelPath.isDirectory();
    }
}
