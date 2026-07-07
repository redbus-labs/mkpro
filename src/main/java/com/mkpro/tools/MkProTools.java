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
        return new BaseTool("read_file", "Reads the content of a file. Path must be within the project directory.") {
            @Override public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder().name(name()).description(description())
                    .parameters(Schema.builder().type("OBJECT")
                        .properties(ImmutableMap.of("path", Schema.builder().type("STRING").description("Path to the file").build()))
                        .required(ImmutableList.of("path")).build()).build());
            }
            @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String pathStr = (String) args.get("path");
                    try {
                        Path validated = com.mkpro.security.PathValidator.getInstance().validateForRead(pathStr);
                        return Collections.singletonMap("content", (Object) Files.readString(validated));
                    } catch (SecurityException e) {
                        return Collections.singletonMap("error", (Object) e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createListDirTool() {
        return new BaseTool("list_dir", "Lists files in a directory. Path must be within the project directory.") {
            @Override public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder().name(name()).description(description())
                    .parameters(Schema.builder().type("OBJECT")
                        .properties(ImmutableMap.of("path", Schema.builder().type("STRING").description("Path to the directory").build()))
                        .required(ImmutableList.of("path")).build()).build());
            }
            @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String pathStr = (String) args.get("path");
                    try {
                        Path validated = com.mkpro.security.PathValidator.getInstance().validateForRead(pathStr);
                        try (var stream = Files.list(validated)) {
                            List<String> files = stream.map(p -> p.getFileName().toString()).collect(Collectors.toList());
                            return Collections.<String, Object>singletonMap("files", files);
                        }
                    } catch (SecurityException e) {
                        return Collections.<String, Object>singletonMap("error", e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createWriteFileTool() {
        return new BaseTool("write_file", "Writes content to a file. Path must be within the project directory.") {
            @Override public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder().name(name()).description(description())
                    .parameters(Schema.builder().type("OBJECT")
                        .properties(ImmutableMap.of(
                            "path", Schema.builder().type("STRING").description("Path to the file.").build(),
                            "content", Schema.builder().type("STRING").description("Content to write.").build()))
                        .required(ImmutableList.of("path", "content")).build()).build());
            }
            @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String pathStr = (String) args.get("path");
                    String content = (String) args.get("content");
                    try {
                        Path validated = com.mkpro.security.PathValidator.getInstance().validate(pathStr);
                        if (validated.getParent() != null) {
                            Files.createDirectories(validated.getParent());
                        }
                        Files.writeString(validated, content);
                        return Collections.<String, Object>singletonMap("status", "Success");
                    } catch (SecurityException e) {
                        return Collections.<String, Object>singletonMap("error", e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createSafeWriteFileTool() {
        return new BaseTool(
                "safe_write_file",
                "Writes content to a file safely. It shows a preview of the changes and asks for user confirmation before overwriting."
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
                                                .description("The path to the file.")
                                                .build(),
                                        "content", Schema.builder()
                                                .type("STRING")
                                                .description("The content to write.")
                                                .build()
                                ))
                                .required(ImmutableList.of("path", "content"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String filePath = (String) args.get("path");
                if (filePath == null) {
                    filePath = (String) args.get("file_path");
                }
                final String finalFilePath = filePath;
                final String newContent = (String) args.get("content");
                
                return Single.fromCallable(() -> {
                    try {
                        // Validate path before any operation
                        Path path = com.mkpro.security.PathValidator.getInstance().validate(finalFilePath);
                        String oldContent = "";
                        if (Files.exists(path)) {
                            oldContent = Files.readString(path);
                        } else {
                             System.out.println(ANSI_BLUE + "[CodeEditor] Creating NEW file: " + finalFilePath + ANSI_RESET);
                        }

                        System.out.println(ANSI_BLUE + "\n--- PROPOSED CHANGES FOR: " + finalFilePath + " ---" + ANSI_RESET);
                        
                        // Simple Diff Preview
                        String[] oldLines = oldContent.split("\n");
                        String[] newLines = newContent.split("\n");
                        
                        // Heuristic: If file is huge, just show head/tail or size diff
                        if (newLines.length > 50 && oldLines.length > 50) {
                            System.out.println(ANSI_YELLOW + "File is large (" + newLines.length + " lines). Showing first 10 and last 10 lines." + ANSI_RESET);
                             for (int i = 0; i < Math.min(10, newLines.length); i++) {
                                System.out.println(ANSI_GREEN + "+ " + newLines[i] + ANSI_RESET);
                            }
                            System.out.println("...");
                            for (int i = Math.max(0, newLines.length - 10); i < newLines.length; i++) {
                                System.out.println(ANSI_GREEN + "+ " + newLines[i] + ANSI_RESET);
                            }
                        } else {
                            // Let's try a very basic diff logic:
                            int maxLen = Math.max(oldLines.length, newLines.length);
                            boolean hasChanges = false;
                            
                            for (int i = 0; i < maxLen; i++) {
                                String oldL = (i < oldLines.length) ? oldLines[i] : null;
                                String newL = (i < newLines.length) ? newLines[i] : null;
                                
                                if (oldL == null && newL != null) {
                                    System.out.println(ANSI_GREEN + "+ " + newL + ANSI_RESET);
                                    hasChanges = true;
                                } else if (oldL != null && newL == null) {
                                    System.out.println(ANSI_RED + "- " + oldL + ANSI_RESET);
                                    hasChanges = true;
                                } else if (!oldL.equals(newL)) {
                                    System.out.println(ANSI_RED + "- " + oldL + ANSI_RESET);
                                    System.out.println(ANSI_GREEN + "+ " + newL + ANSI_RESET);
                                    hasChanges = true;
                                }
                            }
                            
                            if (!hasChanges) {
                                System.out.println(ANSI_YELLOW + "No textual changes detected." + ANSI_RESET);
                            }
                        }

                        System.out.println(ANSI_BLUE + "---------------------------------------------" + ANSI_RESET);
                        
                        // Auto-approve logic
                        System.out.print(ANSI_YELLOW + "Auto-approving in 7s... (Press Enter to pause/reject) " + ANSI_RESET);
                        
                        boolean interrupted = false;
                        for (int i = 7; i > 0; i--) {
                            System.out.print("\r" + ANSI_YELLOW + "Auto-approving in " + i + "s... (Press Enter to pause/reject)   " + ANSI_RESET);
                            // Check if input is available (non-blocking check)
                            try {
                                if (System.in.available() > 0) {
                                    interrupted = true;
                                    break;
                                }
                                Thread.sleep(1000);
                            } catch (Exception e) {
                                // Ignore
                            }
                        }
                        System.out.println(); // Newline

                        if (!interrupted) {
                            System.out.println(ANSI_GREEN + "Time's up! Auto-approving changes." + ANSI_RESET);
                            if (path.getParent() != null) {
                                Files.createDirectories(path.getParent());
                            }
                            if (Files.exists(path)) {
                                System.out.println(ANSI_BLUE + "Creating backup..." + ANSI_RESET);
                                Maker.backItUp(path.toFile());
                            }
                            Files.writeString(path, newContent, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                            return Collections.singletonMap("status", "File written successfully (Auto-approved): " + finalFilePath);
                        }

                        // Fallback to manual confirmation if interrupted
                        System.out.print(ANSI_YELLOW + "Apply these changes? [y/N]: " + ANSI_RESET);
                        Scanner scanner = new Scanner(System.in);
                        if (scanner.hasNextLine()) {
                            String input = scanner.nextLine().trim();
                            if ("y".equalsIgnoreCase(input) || "yes".equalsIgnoreCase(input)) {
                                if (path.getParent() != null) {
                                    Files.createDirectories(path.getParent());
                                }
                                if (Files.exists(path)) {
                                    System.out.println(ANSI_BLUE + "Creating backup..." + ANSI_RESET);
                                    Maker.backItUp(path.toFile());
                                }
                                Files.writeString(path, newContent, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                                System.out.println(ANSI_GREEN + "File written successfully." + ANSI_RESET);
                                return Collections.singletonMap("status", "File written successfully: " + finalFilePath);
                            } else {
                                System.out.println(ANSI_RED + "Changes rejected by user." + ANSI_RESET);
                                return Collections.singletonMap("status", "User rejected changes for: " + finalFilePath);
                            }
                        }
                        
                        return Collections.singletonMap("status", "No input received. Changes rejected.");

                    } catch (SecurityException se) {
                        return Collections.singletonMap("error", (Object) se.getMessage());
                    } catch (IOException e) {
                        return Collections.singletonMap("error", (Object) ("Error processing safe write: " + e.getMessage()));
                    }
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
        return new BaseTool("run_shell", "Executes a shell command with timeout and output limits. Commands are checked against an allowlist policy.") {
            @Override public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder().name(name()).description(description())
                    .parameters(Schema.builder().type("OBJECT")
                        .properties(ImmutableMap.of(
                            "command", Schema.builder().type("STRING").description("The shell command to execute.").build(),
                            "working_dir", Schema.builder().type("STRING").description("Optional working directory for command execution.").build(),
                            "timeout_seconds", Schema.builder().type("INTEGER").description("Optional timeout in seconds (default 120).").build()
                        ))
                        .required(ImmutableList.of("command")).build()).build());
            }
            @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String command = (String) args.get("command");
                    String workingDir = (String) args.get("working_dir");
                    Object timeoutObj = args.get("timeout_seconds");
                    int timeout = 120;
                    if (timeoutObj instanceof Number) {
                        timeout = Math.min(((Number) timeoutObj).intValue(), 600); // Cap at 10 minutes
                    }

                    System.out.println(ANSI_BLUE + "[Shell] $ " + command + ANSI_RESET);

                    com.mkpro.security.ShellExecutor executor = new com.mkpro.security.ShellExecutor(timeout, 100 * 1024);
                    com.mkpro.security.ShellExecutor.ExecutionResult result = executor.execute(command, workingDir);

                    if (result.isTimedOut()) {
                        System.out.println(ANSI_RED + "[Shell] Command timed out after " + timeout + "s" + ANSI_RESET);
                    } else if (result.getExitCode() != 0 && result.getExitCode() != -1) {
                        System.out.println(ANSI_YELLOW + "[Shell] Exit code: " + result.getExitCode() + ANSI_RESET);
                    }

                    Map<String, Object> response = new java.util.HashMap<>();
                    response.put("output", result.toAgentResponse());
                    response.put("exit_code", result.getExitCode());
                    response.put("timed_out", result.isTimedOut());
                    response.put("truncated", result.isOutputTruncated());
                    response.put("duration_ms", result.getDurationMs());
                    return response;
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
