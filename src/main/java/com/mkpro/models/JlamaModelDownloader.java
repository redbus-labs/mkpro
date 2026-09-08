package com.mkpro.models;

import com.github.tjake.jlama.util.Downloader;

import java.io.File;

/**
 * Standalone entry point to download a Jlama model from HuggingFace.
 * Used by jlama-download.bat without starting the full mkpro application.
 *
 * Usage: java -cp mkpro.jar com.mkpro.models.JlamaModelDownloader <model-name> [models-dir]
 */
public class JlamaModelDownloader {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: JlamaModelDownloader <owner/model-name> [models-directory]");
            System.err.println("Example: JlamaModelDownloader tjake/Llama-3.2-1B-Instruct-JQ4");
            System.exit(1);
        }

        String modelName = args[0];
        String modelsDir = args.length > 1 ? args[1] 
            : System.getProperty("user.home") + File.separator + "Documents" + File.separator + "mkpro" + File.separator + "jlama-models";

        if (!modelName.contains("/")) {
            System.err.println("Error: Model name must be in owner/name format (e.g., tjake/Llama-3.2-1B-Instruct-JQ4)");
            System.exit(1);
        }

        // Check if already downloaded
        File modelPath = new File(modelsDir, modelName.replace('/', File.separatorChar));
        if (modelPath.exists()) {
            System.out.println("  ✓ Already downloaded: " + modelPath.getAbsolutePath());
            System.exit(0);
        }

        System.out.println("  Downloading " + modelName + " from HuggingFace...");
        System.out.println("  Target: " + modelsDir);
        System.out.println();

        try {
            Downloader downloader = new Downloader(modelsDir, modelName);
            File localPath = downloader.huggingFaceModel();
            System.out.println();
            System.out.println("  ✓ Download complete: " + localPath.getAbsolutePath());
            System.out.println();
            System.out.println("  To use in mkpro:");
            System.out.println("    /config <agent> " + modelName + "@jlama");
        } catch (Exception e) {
            System.err.println("  ✗ Download failed: " + e.getMessage());
            System.exit(1);
        }
    }
}
