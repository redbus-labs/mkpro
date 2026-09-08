package com.mkpro.models;

import com.github.tjake.jlama.util.Downloader;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages local Jlama model storage: download, list, remove.
 * Models are stored in ~/Documents/mkpro/jlama-models/ by default.
 */
public class JlamaModelRegistry {

    private static final String DEFAULT_MODELS_DIR = "jlama-models";
    private final Path modelsRoot;

    public JlamaModelRegistry() {
        this(getDefaultModelsPath());
    }

    public JlamaModelRegistry(Path modelsRoot) {
        this.modelsRoot = modelsRoot;
        try {
            Files.createDirectories(modelsRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create models directory: " + modelsRoot, e);
        }
    }

    /**
     * Download a model from HuggingFace.
     * @param modelName Full HF name (e.g., "tjake/Llama-3.2-1B-Instruct-JQ4")
     * @return The local path where model was downloaded
     */
    public File downloadModel(String modelName) throws IOException {
        System.out.println("  Downloading: " + modelName + " → " + modelsRoot);
        Downloader downloader = new Downloader(modelsRoot.toString(), modelName);
        File localPath = downloader.huggingFaceModel();
        System.out.println("  ✓ Download complete: " + localPath.getAbsolutePath());
        return localPath;
    }

    /**
     * List all locally available models.
     */
    public List<ModelInfo> listModels() {
        List<ModelInfo> models = new ArrayList<>();
        File root = modelsRoot.toFile();
        if (!root.exists()) return models;

        File[] entries = root.listFiles(File::isDirectory);
        if (entries == null) return models;

        for (File entry : entries) {
            // Jlama Downloader uses flat format: "owner_modelname"
            if (isValidModelDir(entry)) {
                // Convert "tjake_Llama-3.2-1B-Instruct-JQ4" → "tjake/Llama-3.2-1B-Instruct-JQ4"
                String name = entry.getName();
                int firstUnderscore = name.indexOf('_');
                if (firstUnderscore > 0) {
                    name = name.substring(0, firstUnderscore) + "/" + name.substring(firstUnderscore + 1);
                }
                long size = directorySize(entry.toPath());
                models.add(new ModelInfo(name, entry.toPath(), size));
            } else {
                // Check nested: owner/name format (manual installs)
                File[] subDirs = entry.listFiles(File::isDirectory);
                if (subDirs != null) {
                    for (File modelDir : subDirs) {
                        if (isValidModelDir(modelDir)) {
                            String name = entry.getName() + "/" + modelDir.getName();
                            long size = directorySize(modelDir.toPath());
                            models.add(new ModelInfo(name, modelDir.toPath(), size));
                        }
                    }
                }
            }
        }
        return models;
    }

    /**
     * Remove a model from local storage.
     * @return true if removed, false if not found
     */
    public boolean removeModel(String modelName) {
        Path modelPath = resolveModelPath(modelName);
        if (modelPath == null || !Files.exists(modelPath)) return false;

        try {
            // Evict from in-memory cache first
            JlamaProvider.evictFromCache(modelName);

            // Recursively delete
            Files.walkFileTree(modelPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });

            // Clean up empty owner directory
            Path ownerDir = modelPath.getParent();
            if (ownerDir != null && isDirEmpty(ownerDir)) {
                Files.deleteIfExists(ownerDir);
            }
            return true;
        } catch (IOException e) {
            System.err.println("[JlamaRegistry] Failed to remove " + modelName + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if a model is available locally.
     */
    public boolean isDownloaded(String modelName) {
        Path modelPath = resolveModelPath(modelName);
        return modelPath != null && Files.exists(modelPath) && isValidModelDir(modelPath.toFile());
    }

    /**
     * Get the local path for a model.
     */
    public Path getModelPath(String modelName) {
        Path resolved = resolveModelPath(modelName);
        return resolved != null ? resolved : modelsRoot.resolve(modelName.replace('/', File.separatorChar));
    }

    /**
     * Resolve model path — checks both flat (owner_name) and nested (owner/name) formats.
     */
    private Path resolveModelPath(String modelName) {
        // Try flat format first (how Jlama Downloader stores it): owner_name
        Path flat = modelsRoot.resolve(modelName.replace('/', '_'));
        if (Files.exists(flat)) return flat;

        // Try nested: owner/name
        Path nested = modelsRoot.resolve(modelName.replace('/', File.separatorChar));
        if (Files.exists(nested)) return nested;

        return null;
    }

    public Path getModelsRoot() {
        return modelsRoot;
    }

    // ═══ Helpers ═══

    private boolean isValidModelDir(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (name.equals("config.json") || name.endsWith(".safetensors") || name.equals("tokenizer.json")) {
                return true;
            }
        }
        return false;
    }

    private long directorySize(Path dir) {
        AtomicLong size = new AtomicLong(0);
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    size.addAndGet(attrs.size());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) { /* ignore */ }
        return size.get();
    }

    private boolean isDirEmpty(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findFirst().isEmpty();
        }
    }

    private static Path getDefaultModelsPath() {
        String userHome = System.getProperty("user.home");
        return Path.of(userHome, "Documents", "mkpro", DEFAULT_MODELS_DIR);
    }

    // ═══ Model Info ═══

    public record ModelInfo(String name, Path path, long sizeBytes) {
        public String formattedSize() {
            if (sizeBytes < 1024) return sizeBytes + " B";
            if (sizeBytes < 1024 * 1024) return String.format("%.1f KB", sizeBytes / 1024.0);
            if (sizeBytes < 1024 * 1024 * 1024) return String.format("%.1f MB", sizeBytes / (1024.0 * 1024));
            return String.format("%.2f GB", sizeBytes / (1024.0 * 1024 * 1024));
        }
    }
}
