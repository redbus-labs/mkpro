package com.mkpro.tools;

import static com.mkpro.ui.AnsiColors.*;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Single;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * IndexCodebaseTool allows agents (primarily Architect) to trigger project indexing.
 * This populates the vector store for semantic codebase search.
 * 
 * TODO: Replace ZeroEmbeddingService with a real embedding model for meaningful search results.
 * Current implementation indexes file paths and content chunks into the vector store.
 */
public class IndexCodebaseTool {

    private static final String[] INDEXABLE_EXTENSIONS = {
        ".java", ".kt", ".py", ".js", ".ts", ".tsx", ".jsx",
        ".go", ".rs", ".swift", ".dart", ".c", ".cpp", ".h",
        ".xml", ".yaml", ".yml", ".json", ".toml", ".gradle",
        ".md", ".txt", ".sql", ".html", ".css", ".scss"
    };

    private static final String[] EXCLUDED_DIRS = {
        "node_modules", ".git", "build", "target", "dist", "out",
        ".gradle", ".idea", ".vscode", "__pycache__", "venv", ".mkpro"
    };

    private static final int MAX_FILE_SIZE = 50_000; // 50KB max per file
    private static final int CHUNK_SIZE = 1000; // characters per chunk

    public static BaseTool create(com.google.adk.memory.MapDBVectorStore vectorStore,
                                   com.google.adk.memory.EmbeddingService embeddingService) {
        return new BaseTool(
            "index_codebase",
            "Indexes the project source files for semantic search. Run this when you need to search " +
            "the codebase but get no results, or when significant code changes have been made. " +
            "Indexes source files into a vector store so codebase_search can find relevant code."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                    .name(name())
                    .description(description())
                    .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(ImmutableMap.of(
                            "path", Schema.builder()
                                .type("STRING")
                                .description("Optional: subdirectory to index (e.g., 'src/main'). Defaults to project root.")
                                .build()
                        ))
                        .build())
                    .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String pathStr = args.get("path") != null ? (String) args.get("path") : ".";
                    Path root = Paths.get("").toAbsolutePath().resolve(pathStr).normalize();

                    if (!Files.isDirectory(root)) {
                        return Collections.singletonMap("error", (Object) ("Path not found: " + pathStr));
                    }

                    System.out.println(ANSI_BLUE + "[Index] Scanning " + root + "..." + ANSI_RESET);

                    int[] stats = {0, 0}; // [files indexed, chunks created]

                    try (Stream<Path> paths = Files.walk(root)) {
                        paths.filter(Files::isRegularFile)
                            .filter(p -> isIndexable(p))
                            .filter(p -> !isExcluded(p, root))
                            .forEach(file -> {
                                try {
                                    String content = Files.readString(file);
                                    if (content.length() > MAX_FILE_SIZE) {
                                        content = content.substring(0, MAX_FILE_SIZE);
                                    }

                                    String relativePath = root.relativize(file).toString();

                                    // Chunk the file and index each chunk
                                    int offset = 0;
                                    while (offset < content.length()) {
                                        int end = Math.min(offset + CHUNK_SIZE, content.length());
                                        String chunk = content.substring(offset, end);
                                        String indexEntry = "FILE: " + relativePath + "\n" + chunk;

                                        // Generate embedding and store
                                        try {
                                            double[] embedding = embeddingService.generateEmbedding(indexEntry).blockingGet();
                                            com.google.adk.memory.Vector vec = new com.google.adk.memory.Vector(
                                                relativePath + "#" + offset,
                                                indexEntry,
                                                embedding,
                                                Map.of("file", relativePath, "offset", offset)
                                            );
                                            vectorStore.insertVector(vec);
                                            stats[1]++;
                                        } catch (Exception e) {
                                            // Skip chunks that fail to embed
                                        }

                                        offset += CHUNK_SIZE;
                                    }
                                    stats[0]++;
                                } catch (IOException e) {
                                    // Skip unreadable files
                                }
                            });
                    }

                    System.out.println(ANSI_GREEN + "[Index] Done: " + stats[0] + " files, " + stats[1] + " chunks indexed." + ANSI_RESET);

                    return Map.of(
                        "result", "Indexing complete.",
                        "files_indexed", stats[0],
                        "chunks_created", stats[1],
                        "root", root.toString()
                    );
                });
            }
        };
    }

    private static boolean isIndexable(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        for (String ext : INDEXABLE_EXTENSIONS) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    private static boolean isExcluded(Path file, Path root) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        for (String excluded : EXCLUDED_DIRS) {
            if (relative.startsWith(excluded + "/") || relative.contains("/" + excluded + "/")) {
                return true;
            }
        }
        return false;
    }
}
