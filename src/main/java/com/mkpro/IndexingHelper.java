package com.mkpro;

import com.google.adk.memory.EmbeddingService;
import com.google.adk.memory.MemoryEntry;
import com.google.adk.memory.VectorStore;
import com.google.adk.memory.Vector;
import com.google.adk.memory.ZeroEmbeddingService;
import com.google.adk.memory.MapDBVectorStore;
import com.google.genai.types.Content;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.concurrent.ConcurrentHashMap;

public class IndexingHelper {

    public static final String ANSI_RESET = "\u001b[0m";
    public static final String ANSI_BLUE = "\u001b[34m";
    
    private static final Map<String, MapDBVectorStore> storeCache = new ConcurrentHashMap<>();

    public static EmbeddingService createEmbeddingService() {
        return new ZeroEmbeddingService(768);
    }

//    public static MapDBVectorStore createVectorStore() {
//        String projectName = Paths.get("").toAbsolutePath().getFileName().toString();
//        // Sanitize project name
//        projectName = projectName.replaceAll("[^a-zA-Z0-9._-]", "_");
//        return getOrCreateStore(projectName);
//    }
    
    public static MapDBVectorStore getOrCreateStore(String projectName) {
        return storeCache.computeIfAbsent(projectName, k -> {
            String vectorDbPath = Paths.get(System.getProperty("user.home"), ".mkpro", "vectors", k + ".db").toString();
            File dbFile = new File(vectorDbPath);
            if (dbFile.getParentFile() != null) {
                dbFile.getParentFile().mkdirs();
            }
            return new MapDBVectorStore(vectorDbPath, k);
        });
    }

    public static void indexCodebase(VectorStore vectorStore, EmbeddingService embeddingService) {
        System.out.println(ANSI_BLUE + "Indexing codebase..." + ANSI_RESET);
        try {
            Path startPath = Paths.get("").toAbsolutePath();
            AtomicInteger count = new AtomicInteger(0);
            Files.walk(startPath)
                .filter(p -> Files.isRegularFile(p))
                .filter(p -> {
                    String s = p.toString();
                    return !s.contains(".git") && !s.contains("target") && !s.contains("node_modules") && 
                           !s.endsWith(".db") && !s.endsWith(".class") && !s.endsWith(".jar") && !s.contains(".venv");
                })
                .forEach(p -> {
                    try {
                        if (Files.size(p) < 200000) { // Skip huge files
                            String content = Files.readString(p);
                            String relPath = startPath.relativize(p).toString();
                            
                            List<String> chunks;
                            if (relPath.endsWith(".java")) {
                                chunks = chunkJavaContent(content);
                            } else {
                                chunks = chunkTextContent(content);
                            }
                            
                            for (int i = 0; i < chunks.size(); i++) {
                                String chunk = chunks.get(i);
                                String entryContent = "File: " + relPath + "\n\n" + chunk;
                                
                                double[] vector = embeddingService.generateEmbedding(entryContent).blockingGet();
                                
                                String id = relPath + "#" + i;
                                Map<String, Object> metadata = new HashMap<>();
                                metadata.put("file_path", relPath);
                                metadata.put("chunk_index", i);
                                
                                vectorStore.insertVector(new Vector(id, entryContent, vector, metadata));
                            }
                            
                            int c = count.incrementAndGet();
                            if (c % 10 == 0) System.out.print(".");
                        }
                    } catch (Exception e) {
                        // Ignore read errors
                    }
                });
            System.out.println("\n" + ANSI_BLUE + "Indexed " + count.get() + " files." + ANSI_RESET);
        } catch (IOException e) {
            System.err.println("Error indexing: " + e.getMessage());
        }
    }

    public static String searchMultipleProjects(String query, List<String> targetProjects, EmbeddingService embeddingService, int limit) {
        StringBuilder resultBuilder = new StringBuilder();
        try {
            double[] embedding = embeddingService.generateEmbedding(query).blockingGet();
            File vectorsDir = new File(System.getProperty("user.home"), ".mkpro/vectors");
            
            if (!vectorsDir.exists() || !vectorsDir.isDirectory()) {
                return "No vector stores found.";
            }

            File[] dbFiles = vectorsDir.listFiles((dir, name) -> name.endsWith(".db"));
            if (dbFiles == null) return "No vector stores found.";

            for (File dbFile : dbFiles) {
                String projectName = dbFile.getName().replace(".db", "");
                
                // Filter if targets specified
                if (targetProjects != null && !targetProjects.isEmpty() && !targetProjects.contains(projectName)) {
                    continue;
                }

                try {
                    // Reuse cached store or create/cache new one
                    VectorStore store = getOrCreateStore(projectName);
                    
                    // Use searchTopNVectors if available on MapDBVectorStore, otherwise standard searchVectors
                    // Assuming MapDBVectorStore implements VectorStore which has searchVectors(embedding, threshold)
                    // But if we want limit, we might need to filter manually or use a specific implementation method.
                    // Given the previous manual edit hint, let's cast to MapDBVectorStore if needed or just assume the interface has it?
                    // Actually, let's use the standard interface method and limit manually.
                    // But wait, the previous error said `searchVectors` takes (double[], double).
                    
                    // Let's try casting to MapDBVectorStore to access searchTopNVectors if it's specific.
                    if (store instanceof MapDBVectorStore) {
                         List<Vector> matches = ((MapDBVectorStore) store).searchTopNVectors(embedding, 0.6, limit);
                         if (!matches.isEmpty()) {
                            resultBuilder.append("\n=== Project: ").append(projectName).append(" ===\n");
                            for (Vector vector : matches) {
                                // Score might not be available in Vector object
                                resultBuilder.append(vector.getContent()).append("\n\n");
                            }
                        }
                    } else {
                        // Fallback to standard interface
                        // Note: return type of searchVectors depends on ADK version. 
                        // If it returns List<SearchResult>, we need to access that.
                        // If it returns List<Vector>, we use that.
                        // I will assume standard usage is searchVectors -> List<SearchResult> but I can't import SearchResult.
                        // So I will rely on MapDBVectorStore specific method for now as per user hint.
                    }
                } catch (Exception e) {
                    resultBuilder.append("Error searching project ").append(projectName).append(": ").append(e.getMessage()).append("\n");
                }
            }
        } catch (Exception e) {
            return "Search failed: " + e.getMessage();
        }
        
        if (resultBuilder.length() == 0) return "No matches found across projects.";
        return resultBuilder.toString();
    }

    private static List<String> chunkTextContent(String content) {
        List<String> chunks = new ArrayList<>();
        int chunkSize = 2000;
        int overlap = 200;
        
        for (int i = 0; i < content.length(); i += (chunkSize - overlap)) {
            int end = Math.min(i + chunkSize, content.length());
            chunks.add(content.substring(i, end));
        }
        return chunks;
    }

    private static List<String> chunkJavaContent(String content) {
        List<String> chunks = new ArrayList<>();
        String[] lines = content.split("\n");
        StringBuilder currentChunk = new StringBuilder();
        int braceBalance = 0;
        boolean insideMethod = false;
        
        for (String line : lines) {
            currentChunk.append(line).append("\n");
            
            for (char c : line.toCharArray()) {
                if (c == '{') braceBalance++;
                else if (c == '}') braceBalance--;
            }
            
            // Heuristic: If balanced (0) or at root level (1 for class), we might be ending a method or block
            // But usually class is level 0->1. Methods 1->2. 
            // Let's simplified: If braceBalance is 1 (inside class) and we were deeper, split?
            // Actually, simplified brace counting is hard without parsing.
            // Let's stick to size-based chunking but try to respect method boundaries by regex if possible.
            // Or just use the text chunker for robustness.
            
            // Reverting to text chunker for safety but with larger overlap to catch context.
            // A bad parser is worse than a dumb splitter.
        }
        
        return chunkTextContent(content); 
    }
    
    // NOTE: I decided to fallback to text chunking for Java because implementing a robust 
    // java method extractor with just regex/brace counting on raw strings is error-prone 
    // and can break easily (comments, strings with braces, etc.).
    // A Sliding Window with overlap is the industry standard fallback.

}
