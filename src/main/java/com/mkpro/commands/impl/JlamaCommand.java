package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.models.JlamaModelRegistry;
import com.mkpro.models.JlamaModelRegistry.ModelInfo;
import static com.mkpro.ui.AnsiColors.*;

import java.io.File;
import java.util.List;

/**
 * /jlama command — manage local Jlama models (pure Java LLM inference).
 *
 * Usage:
 *   /jlama                    - Show status (models dir, downloaded models)
 *   /jlama list               - List downloaded models with sizes
 *   /jlama download <model>   - Download a model from HuggingFace
 *   /jlama rm <model>         - Remove a downloaded model
 *   /jlama models             - Show recommended models for download
 */
public class JlamaCommand implements Command {

    private final JlamaModelRegistry registry = new JlamaModelRegistry();

    @Override
    public String getName() {
        return "jlama";
    }

    @Override
    public String getDescription() {
        return "Manage local Jlama models (download, list, rm). Usage: /jlama [list|download|rm|models]";
    }

    @Override
    public void execute(String[] args, MkProContext context) {
        if (args.length == 0) {
            showStatus();
            return;
        }

        switch (args[0].toLowerCase()) {
            case "list", "ls" -> listModels();
            case "download", "pull" -> downloadModel(args);
            case "rm", "remove", "delete" -> removeModel(args);
            case "models", "recommended" -> showRecommended();
            default -> showStatus();
        }
    }

    private void showStatus() {
        System.out.println(ANSI_CYAN + "\n── Jlama (Pure Java LLM Inference) ──" + ANSI_RESET);
        System.out.println("  Models directory: " + registry.getModelsRoot());
        
        List<ModelInfo> models = registry.listModels();
        if (models.isEmpty()) {
            System.out.println("  Downloaded models: " + ANSI_YELLOW + "none" + ANSI_RESET);
        } else {
            System.out.println("  Downloaded models: " + ANSI_GREEN + models.size() + ANSI_RESET);
            for (ModelInfo m : models) {
                System.out.println("    • " + m.name() + " (" + m.formattedSize() + ")");
            }
        }

        System.out.println();
        System.out.println("  Usage:");
        System.out.println("    /jlama download <owner/model>   Download from HuggingFace");
        System.out.println("    /jlama list                     List local models");
        System.out.println("    /jlama rm <owner/model>         Remove a model");
        System.out.println("    /jlama models                   Show recommended models");
        System.out.println();
        System.out.println("  To use: /config <agent> <model>@jlama");
        System.out.println();

        // Check JVM flags
        boolean hasVector = hasVectorModule();
        if (!hasVector) {
            System.out.println(ANSI_YELLOW + "  ⚠ JVM flag missing: --add-modules jdk.incubator.vector" + ANSI_RESET);
            System.out.println("    Add to JVM args or set: JDK_JAVA_OPTIONS=\"--add-modules jdk.incubator.vector\"");
            System.out.println();
        }
    }

    private void listModels() {
        List<ModelInfo> models = registry.listModels();
        if (models.isEmpty()) {
            System.out.println(ANSI_YELLOW + "  No models downloaded. Use /jlama download <model>" + ANSI_RESET);
            return;
        }

        System.out.println(ANSI_CYAN + "\n  Local Jlama Models:" + ANSI_RESET);
        long totalSize = 0;
        for (ModelInfo m : models) {
            System.out.println("    " + ANSI_GREEN + m.name() + ANSI_RESET + "  " + ANSI_DIM + m.formattedSize() + ANSI_RESET);
            totalSize += m.sizeBytes();
        }
        System.out.println();
        System.out.println("  Total: " + models.size() + " model(s), " + formatSize(totalSize));
        System.out.println();
    }

    private void downloadModel(String[] args) {
        if (args.length < 2) {
            System.out.println(ANSI_YELLOW + "  Usage: /jlama download <owner/model-name>" + ANSI_RESET);
            System.out.println("  Example: /jlama download tjake/Llama-3.2-1B-Instruct-JQ4");
            return;
        }

        String modelName = args[1];
        if (!modelName.contains("/")) {
            System.out.println(ANSI_YELLOW + "  Model name must be in owner/name format (e.g., tjake/Llama-3.2-1B-Instruct-JQ4)" + ANSI_RESET);
            return;
        }

        if (registry.isDownloaded(modelName)) {
            System.out.println(ANSI_GREEN + "  ✓ Already downloaded: " + modelName + ANSI_RESET);
            return;
        }

        System.out.println(ANSI_CYAN + "  Downloading " + modelName + " from HuggingFace..." + ANSI_RESET);
        try {
            File path = registry.downloadModel(modelName);
            System.out.println(ANSI_GREEN + "  ✓ Downloaded: " + path.getAbsolutePath() + ANSI_RESET);
            System.out.println("  Use with: /config <agent> " + modelName + "@jlama");
        } catch (Exception e) {
            System.out.println(ANSI_RED + "  ✗ Download failed: " + e.getMessage() + ANSI_RESET);
        }
    }

    private void removeModel(String[] args) {
        if (args.length < 2) {
            System.out.println(ANSI_YELLOW + "  Usage: /jlama rm <owner/model-name>" + ANSI_RESET);
            return;
        }

        String modelName = args[1];
        if (!registry.isDownloaded(modelName)) {
            System.out.println(ANSI_YELLOW + "  Model not found: " + modelName + ANSI_RESET);
            return;
        }

        if (registry.removeModel(modelName)) {
            System.out.println(ANSI_GREEN + "  ✓ Removed: " + modelName + ANSI_RESET);
        } else {
            System.out.println(ANSI_RED + "  ✗ Failed to remove: " + modelName + ANSI_RESET);
        }
    }

    private void showRecommended() {
        System.out.println(ANSI_CYAN + "\n  Recommended Jlama Models (pre-quantized):" + ANSI_RESET);
        System.out.println();
        System.out.println("  " + ANSI_GREEN + "Small (1-3B, fast, low RAM):" + ANSI_RESET);
        System.out.println("    tjake/Llama-3.2-1B-Instruct-JQ4        ~700 MB   Best for lightweight tasks");
        System.out.println("    tjake/Qwen2.5-1.5B-Instruct-JQ4        ~900 MB   Strong multilingual");
        System.out.println("    tjake/Llama-3.2-3B-Instruct-JQ4        ~1.8 GB   Good balance");
        System.out.println();
        System.out.println("  " + ANSI_GREEN + "Medium (7-8B, capable):" + ANSI_RESET);
        System.out.println("    tjake/Meta-Llama-3.1-8B-Instruct-JQ4   ~4.5 GB   Strong general purpose");
        System.out.println("    tjake/Qwen2.5-7B-Instruct-JQ4          ~4.2 GB   Excellent coding");
        System.out.println("    tjake/Mistral-7B-Instruct-v0.3-JQ4     ~4.1 GB   Fast and capable");
        System.out.println();
        System.out.println("  " + ANSI_GREEN + "Note:" + ANSI_RESET + " JQ4 = 4-bit quantized. Needs ~2x model size in RAM.");
        System.out.println("  Browse more at: https://huggingface.co/tjake");
        System.out.println();
    }

    private boolean hasVectorModule() {
        try {
            // Check if the incubator vector module is accessible
            Class.forName("jdk.incubator.vector.FloatVector");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
