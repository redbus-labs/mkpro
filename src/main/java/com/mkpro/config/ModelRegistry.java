package com.mkpro.config;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;

public class ModelRegistry {
    public static final List<String> GEMINI_MODELS = Arrays.asList(
        "gemini-3.1-flash-lite",
        "gemini-3.1-pro-preview",
        "gemini-3-flash-preview",
        "gemini-3-pro",
        "gemini-3-flash",
        "gemini-2.0-flash",
        "gemini-2.0-flash-lite-preview-02-05",
        "gemini-2.0-pro-exp-02-05",
        "gemini-2.0-flash-thinking-exp-01-21",
        "gemini-1.5-pro",
        "gemini-1.5-pro-latest",
        "gemini-1.5-pro-002",
        "gemini-1.5-flash",
        "gemini-1.5-flash-latest",
        "gemini-1.5-flash-002",
        "gemini-1.5-flash-8b",
        "gemini-1.5-flash-8b-latest",
        "gemini-1.5-flash-8b-001"
    );

    public static final List<String> BEDROCK_MODELS = Arrays.asList(
        "anthropic.claude-3-sonnet-20240229-v1:0", "anthropic.claude-3-haiku-20240307-v1:0",
        "anthropic.claude-3-5-sonnet-20240620-v1:0", "meta.llama3-70b-instruct-v1:0",
        "meta.llama3-8b-instruct-v1:0", "amazon.titan-text-express-v1"
    );

    public static final List<String> OLLAMA_MODELS = Arrays.asList(
        "llama3", "qwen2.5-coder", "mistral", "phi3", "codegemma", "starCoder2"
    );

    public static final List<String> AZURE_MODELS = Arrays.asList(
        "gpt-4o", "gpt-4-turbo", "gpt-35-turbo"
    );

    public static List<String> getAllModels() {
        List<String> all = new ArrayList<>(GEMINI_MODELS);
        all.addAll(BEDROCK_MODELS);
        all.addAll(OLLAMA_MODELS);
        all.addAll(AZURE_MODELS);
        return all;
    }
}
