package com.mkpro.tools;
import java.io.File;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import com.mkpro.Maker;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Single;
import com.mkpro.ActionLogger;
import com.mkpro.CentralMemory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Scanner;
import java.util.Arrays;
import java.util.stream.Collectors;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

import com.google.adk.memory.EmbeddingService;
import com.google.adk.memory.MapDBVectorStore;
import com.google.adk.memory.Vector;

import com.mkpro.utils.IndexingHelper;

public class MkProTools {

    public static final String ANSI_RESET = "\u001b[0m";
    public static final String ANSI_BLUE = "\u001b[34m";
    public static final String ANSI_YELLOW = "\u001b[33m";
    public static final String ANSI_RED = "\u001b[31m";
    public static final String ANSI_GREEN = "\u001b[32m";

    public static BaseTool createSearchCodebaseTool(MapDBVectorStore vectorStore, EmbeddingService embeddingService) {
        return new BaseTool(
                "search_codebase",
                "Semantically searches the codebase using vector embeddings. Use this to find relevant code snippets based on meaning."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "query", Schema.builder()
                                                .type("STRING")
                                                .description("The search query describing what code you are looking for.")
                                                .build()
                                ))
                                .required(ImmutableList.of("query"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String query = (String) args.get("query");
                System.out.println(ANSI_BLUE + "[VectorSearch] Searching for: " + query + ANSI_RESET);
                
                return embeddingService.generateEmbedding(query)
                    .map(embedding -> {
                        List<Vector> results = vectorStore.searchTopNVectors( embedding, 0.0, 5); // Top 5, threshold 0.0
                        
                        if (results.isEmpty()) {
                            return Collections.singletonMap("result", "No relevant code found for query: " + query);
                        }
                        
                        StringBuilder sb = new StringBuilder();
                        sb.append("Found ").append(results.size()).append(" relevant snippets:\n\n");
                        
                        for (int i = 0; i < results.size(); i++) {
                            Vector res = results.get(i);
                            sb.append(res.getContent()); 
                            sb.append("\n\n");
                        }
                        
                        return Collections.<String, Object>singletonMap("result", sb.toString());
                    });
            }
        };
    }

    public static BaseTool createReadClipboardTool() {
        return new BaseTool(
                "read_clipboard",
                "Reads content from the system clipboard. Supports text and images. Images are saved to a temporary file."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Collections.emptyMap())
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                System.out.println(ANSI_BLUE + "[System] Reading clipboard..." + ANSI_RESET);
                return Single.fromCallable(() -> {
                    try {
                        if (java.awt.GraphicsEnvironment.isHeadless()) {
                             return Collections.singletonMap("error", "Cannot access clipboard in headless mode.");
                        }

                        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                        Transferable contents = clipboard.getContents(null);

                        if (contents == null) {
                            return Collections.singletonMap("error", "Clipboard is empty.");
                        }

                        if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                            String text = (String) contents.getTransferData(DataFlavor.stringFlavor);
                            if (text.length() > 20000) {
                                text = text.substring(0, 20000) + "\n...[truncated]";
                            }
                            return ImmutableMap.of(
                                "type", "text",
                                "content", text
                            );
                        } else if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                            BufferedImage image = (BufferedImage) contents.getTransferData(DataFlavor.imageFlavor);
                            
                            String tempDir = System.getProperty("java.io.tmpdir");
                            String fileName = "clipboard_" + System.currentTimeMillis() + ".png";
                            File outputFile = new File(tempDir, fileName);
                            
                            ImageIO.write(image, "png", outputFile);
                            
                            return ImmutableMap.of(
                                "type", "image",
                                "file_path", outputFile.getAbsolutePath(),
                                "info", "Image saved to temporary file."
                            );
                        } else {
                            return Collections.singletonMap("error", "Clipboard content type not supported (only text or image).");
                        }

                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Failed to read clipboard: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createReadFileTool() {
        return new BaseTool("read_file", "Reads the content of a file.") {
            @Override public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder().name(name()).description(description())
                    .parameters(Schema.builder().type("OBJECT")
                        .properties(ImmutableMap.of("path", Schema.builder().type("STRING").description("Path to the file").build()))
                        .required(ImmutableList.of("path")).build()).build());
            }
            @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String pathStr = (String) args.get("path");
                    return Collections.singletonMap("content", Files.readString(Paths.get(pathStr)));
                });
            }
        };
    }

    public static BaseTool createListDirTool() {
        return new BaseTool("list_dir", "Lists files in a directory.") {
            @Override public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder().name(name()).description(description())
                    .parameters(Schema.builder().type("OBJECT")
                        .properties(ImmutableMap.of("path", Schema.builder().type("STRING").description("Path to the directory").build()))
                        .required(ImmutableList.of("path")).build()).build());
            }
            @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String pathStr = (String) args.get("path");
                    try (var stream = Files.list(Paths.get(pathStr))) {
                        List<String> files = stream.map(p -> p.getFileName().toString()).collect(Collectors.toList());
                        return Collections.singletonMap("files", files);
                    }
                });
            }
        };
    }

    public static BaseTool createWriteFileTool() {
        return new BaseTool("write_file", "Writes content to a file.") {
            @Override public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder().name(name()).description(description())
                    .parameters(Schema.builder().type("OBJECT")
                        .properties(ImmutableMap.of(
                            "path", Schema.builder().type("STRING").build(),
                            "content", Schema.builder().type("STRING").build()))
                        .required(ImmutableList.of("path", "content")).build()).build());
            }
            @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String pathStr = (String) args.get("path");
                    String content = (String) args.get("content");
                    Files.writeString(Paths.get(pathStr), content);
                    return Collections.singletonMap("status", "Success");
                });
            }
        };
    }

    public static BaseTool createSafeWriteFileTool() {
        return new BaseTool("safe_write_file", "Writes content to a file safely (with logging).") {
            @Override public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder().name(name()).description(description())
                    .parameters(Schema.builder().type("OBJECT")
                        .properties(ImmutableMap.of(
                            "path", Schema.builder().type("STRING").build(),
                            "content", Schema.builder().type("STRING").build()))
                        .required(ImmutableList.of("path", "content")).build()).build());
            }
            @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String pathStr = (String) args.get("path");
                    String content = (String) args.get("content");
                    System.out.println(ANSI_YELLOW + "[SafeWrite] Writing to: " + pathStr + ANSI_RESET);
                    Files.writeString(Paths.get(pathStr), content);
                    return Collections.singletonMap("status", "Success");
                });
            }
        };
    }

    public static BaseTool createReadImageTool() {
        return new BaseTool("read_image", "Reads metadata from an image file.") {
            @Override public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder().name(name()).description(description())
                    .parameters(Schema.builder().type("OBJECT")
                        .properties(ImmutableMap.of("path", Schema.builder().type("STRING").build()))
                        .required(ImmutableList.of("path")).build()).build());
            }
            @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String pathStr = (String) args.get("path");
                    BufferedImage img = ImageIO.read(new File(pathStr));
                    return ImmutableMap.of("width", img.getWidth(), "height", img.getHeight(), "format", "unknown");
                });
            }
        };
    }

    public static BaseTool createImageCropTool() {
        return new BaseTool("image_crop", "Crops an image.") {
            @Override public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder().name(name()).description(description())
                    .parameters(Schema.builder().type("OBJECT")
                        .properties(ImmutableMap.of(
                            "path", Schema.builder().type("STRING").build(),
                            "x", Schema.builder().type("INTEGER").build(),
                            "y", Schema.builder().type("INTEGER").build(),
                            "width", Schema.builder().type("INTEGER").build(),
                            "height", Schema.builder().type("INTEGER").build()))
                        .required(ImmutableList.of("path", "x", "y", "width", "height")).build()).build());
            }
            @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String pathStr = (String) args.get("path");
                    int x = (int) args.get("x");
                    int y = (int) args.get("y");
                    int w = (int) args.get("width");
                    int h = (int) args.get("height");
                    BufferedImage img = ImageIO.read(new File(pathStr));
                    BufferedImage cropped = img.getSubimage(x, y, w, h);
                    File output = new File(pathStr + ".cropped.png");
                    ImageIO.write(cropped, "png", output);
                    return Collections.singletonMap("cropped_path", output.getAbsolutePath());
                });
            }
        };
    }

    public static BaseTool createRunShellTool() {
        return new BaseTool("run_shell", "Executes a shell command.") {
            @Override public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder().name(name()).description(description())
                    .parameters(Schema.builder().type("OBJECT")
                        .properties(ImmutableMap.of("command", Schema.builder().type("STRING").build()))
                        .required(ImmutableList.of("command")).build()).build());
            }
            @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String command = (String) args.get("command");
                    Process p = new ProcessBuilder("cmd.exe", "/c", command).start();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                        String output = reader.lines().collect(Collectors.joining("\n"));
                        return Collections.singletonMap("output", output);
                    }
                });
            }
        };
    }

    public static BaseTool createMultiProjectSearchTool(EmbeddingService embeddingService, MapDBVectorStore vectorStore) {
        return new BaseTool("multi_project_search", "Semantically searches across multiple projects.") {
            @Override public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder().name(name()).description(description())
                    .parameters(Schema.builder().type("OBJECT")
                        .properties(ImmutableMap.of("query", Schema.builder().type("STRING").build()))
                        .required(ImmutableList.of("query")).build()).build());
            }
            @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String query = (String) args.get("query");
                return embeddingService.generateEmbedding(query).map(embedding -> {
                    List<Vector> results = vectorStore.searchTopNVectors(embedding, 0.0, 10);
                    return Collections.singletonMap("results", results.stream().map(Vector::getContent).collect(Collectors.toList()));
                });
            }
        };
    }
}
