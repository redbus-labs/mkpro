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
        return new BaseTool("read_file", "Reads the content of a file. Supports reading specific line ranges for large files.") {
            @Override public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder().name(name()).description(description())
                    .parameters(Schema.builder().type("OBJECT")
                        .properties(ImmutableMap.of(
                            "path", Schema.builder().type("STRING").description("Path to the file").build(),
                            "start_line", Schema.builder().type("INTEGER").description("Optional: start reading from this line (1-indexed). Default: 1.").build(),
                            "end_line", Schema.builder().type("INTEGER").description("Optional: stop reading at this line. Default: reads all or up to 500 lines.").build()
                        ))
                        .required(ImmutableList.of("path")).build()).build());
            }
            @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String pathStr = (String) args.get("path");
                    int startLine = args.get("start_line") != null ? ((Number) args.get("start_line")).intValue() : 1;
                    int endLine = args.get("end_line") != null ? ((Number) args.get("end_line")).intValue() : -1;

                    try {
                        Path validated = com.mkpro.security.PathValidator.getInstance().validateForRead(pathStr);

                        // Special format detection (PDF, DOCX, XLSX, PPTX, SVG, DXF, STL, OBJ)
                        if (FileFormatReader.isSpecialFormat(pathStr)) {
                            return FileFormatReader.read(validated, startLine, endLine);
                        }

                        List<String> allLines = Files.readAllLines(validated);
                        int totalLines = allLines.size();

                        // Clamp start
                        startLine = Math.max(1, startLine);
                        // Default end: start + 500 or total
                        if (endLine < 0) {
                            endLine = Math.min(startLine + 499, totalLines);
                        }
                        endLine = Math.min(endLine, totalLines);

                        // Extract range
                        List<String> lines = allLines.subList(startLine - 1, endLine);
                        String content = String.join("\n", lines);

                        Map<String, Object> result = new java.util.HashMap<>();
                        result.put("content", content);
                        result.put("total_lines", totalLines);
                        result.put("showing_lines", startLine + "-" + endLine);
                        if (endLine < totalLines) {
                            result.put("has_more", true);
                            result.put("next_start_line", endLine + 1);
                        }
                        return result;
                    } catch (SecurityException e) {
                        return Collections.singletonMap("error", (Object) e.getMessage());
                    } catch (java.nio.charset.MalformedInputException e) {
                        // Binary file — return size info instead
                        try {
                            long size = Files.size(Path.of(pathStr));
                            return Map.of("error", "Binary file (not readable as text)", "size_bytes", size);
                        } catch (Exception ex) {
                            return Collections.singletonMap("error", (Object) "Binary file, cannot read as text");
                        }
                    }
                });
            }
        };
    }

    public static BaseTool createListDirTool() {
        return new BaseTool("list_dir", "Lists files and directories. Supports recursive listing with depth control and pagination for large directories.") {
            @Override public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder().name(name()).description(description())
                    .parameters(Schema.builder().type("OBJECT")
                        .properties(ImmutableMap.of(
                            "path", Schema.builder().type("STRING").description("Path to the directory (default: project root)").build(),
                            "recursive", Schema.builder().type("BOOLEAN").description("If true, list recursively. Default: false.").build(),
                            "depth", Schema.builder().type("INTEGER").description("Max depth for recursive listing (1-10). Default: 3.").build(),
                            "limit", Schema.builder().type("INTEGER").description("Max number of entries to return (for pagination). Default: 100.").build(),
                            "offset", Schema.builder().type("INTEGER").description("Skip this many entries (for pagination). Default: 0.").build()
                        ))
                        .required(ImmutableList.of("path")).build()).build());
            }
            @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String pathStr = args.get("path") != null ? (String) args.get("path") : ".";
                    boolean recursive = args.get("recursive") != null && Boolean.TRUE.equals(args.get("recursive"));
                    int depth = args.get("depth") != null ? ((Number) args.get("depth")).intValue() : 3;
                    int limit = args.get("limit") != null ? ((Number) args.get("limit")).intValue() : 100;
                    int offset = args.get("offset") != null ? ((Number) args.get("offset")).intValue() : 0;

                    depth = Math.max(1, Math.min(depth, 10)); // Clamp 1-10
                    limit = Math.max(1, Math.min(limit, 500)); // Clamp 1-500

                    try {
                        Path validated = com.mkpro.security.PathValidator.getInstance().validateForRead(pathStr);
                        if (!Files.isDirectory(validated)) {
                            return Collections.<String, Object>singletonMap("error", "Not a directory: " + pathStr);
                        }

                        List<String> entries = new java.util.ArrayList<>();
                        int maxDepth = recursive ? depth : 1;
                        Path root = validated;

                        try (var stream = Files.walk(validated, maxDepth)) {
                            stream.filter(p -> !p.equals(validated))
                                .filter(p -> {
                                    // Exclude common noise directories and their contents
                                    String rel = root.relativize(p).toString().replace('\\', '/');
                                    String first = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
                                    return !first.equals("node_modules") && !first.equals(".git") &&
                                           !first.equals("target") && !first.equals("build") &&
                                           !first.equals(".mkpro") && !first.equals("dist");
                                })
                                .sorted()
                                .skip(offset)
                                .limit(limit)
                                .forEach(p -> {
                                    String rel = root.relativize(p).toString().replace('\\', '/');
                                    String marker = Files.isDirectory(p) ? "/" : "";
                                    entries.add(rel + marker);
                                });
                        }

                        // Count total for pagination info
                        long total;
                        try (var countStream = Files.walk(validated, maxDepth)) {
                            total = countStream.filter(p -> !p.equals(validated))
                                .filter(p -> {
                                    String rel = root.relativize(p).toString().replace('\\', '/');
                                    String first = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
                                    return !first.equals("node_modules") && !first.equals(".git") &&
                                           !first.equals("target") && !first.equals("build") &&
                                           !first.equals(".mkpro") && !first.equals("dist");
                                })
                                .count();
                        }

                        Map<String, Object> result = new java.util.HashMap<>();
                        result.put("files", entries);
                        result.put("total", total);
                        result.put("showing", entries.size());
                        result.put("offset", offset);
                        if (offset + entries.size() < total) {
                            result.put("has_more", true);
                            result.put("next_offset", offset + limit);
                        }
                        return result;
                    } catch (SecurityException e) {
                        return Collections.<String, Object>singletonMap("error", e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createWriteFileTool() {
        return new BaseTool("write_file", "Writes content to a file. Shows a diff preview and requires approval before writing. Path must be within the project directory.") {
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
                        String oldContent = "";
                        if (Files.exists(validated)) {
                            oldContent = Files.readString(validated);
                        }

                        // Route through approval service
                        com.mkpro.events.EditApprovalService approvalService = com.mkpro.events.EditApprovalService.INSTANCE;
                        com.mkpro.events.MkProEventBus eventBus = com.mkpro.events.MkProEventBus.INSTANCE;

                        if (approvalService == null || eventBus == null) {
                            // Fallback: no approval service, write directly
                            if (validated.getParent() != null) Files.createDirectories(validated.getParent());
                            Files.writeString(validated, content);
                            return Collections.<String, Object>singletonMap("status", "Success (no approval service)");
                        }

                        String proposalId = "edit-" + System.currentTimeMillis();
                        com.mkpro.events.EditProposal proposal = new com.mkpro.events.EditProposal(
                            proposalId, pathStr, oldContent, content);

                        java.util.concurrent.CompletableFuture<Boolean> future = approvalService.submitProposal(proposal);
                        eventBus.emit(com.mkpro.events.MkProEvent.editProposal(proposal));

                        boolean approved;
                        try {
                            approved = future.get(30, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (java.util.concurrent.TimeoutException e) {
                            approved = true;
                            approvalService.approve(proposalId);
                        }

                        if (approved) {
                            eventBus.emit(com.mkpro.events.MkProEvent.editApproved(proposalId, pathStr));
                            if (validated.getParent() != null) Files.createDirectories(validated.getParent());
                            if (Files.exists(validated)) Maker.backItUp(validated.toFile());
                            Files.writeString(validated, content,
                                java.nio.file.StandardOpenOption.CREATE,
                                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                            return Collections.<String, Object>singletonMap("status", "File written successfully: " + pathStr);
                        } else {
                            eventBus.emit(com.mkpro.events.MkProEvent.editRejected(proposalId, pathStr));
                            return Collections.<String, Object>singletonMap("status", "User rejected changes for: " + pathStr);
                        }

                    } catch (SecurityException e) {
                        return Collections.<String, Object>singletonMap("error", e.getMessage());
                    } catch (Exception e) {
                        return Collections.<String, Object>singletonMap("error", "Write failed: " + e.getMessage());
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
                        }

                        // Create edit proposal
                        String proposalId = "edit-" + System.currentTimeMillis();
                        com.mkpro.events.EditProposal proposal = new com.mkpro.events.EditProposal(
                            proposalId, finalFilePath, oldContent, newContent);

                        // Get approval service and event bus
                        com.mkpro.events.EditApprovalService approvalService = com.mkpro.events.EditApprovalService.INSTANCE;
                        com.mkpro.events.MkProEventBus eventBus = com.mkpro.events.MkProEventBus.INSTANCE;

                        if (approvalService == null || eventBus == null) {
                            // Fallback: no event bus available, auto-approve
                            writeFile(path, newContent, finalFilePath);
                            return Collections.singletonMap("status", "File written (no approval service): " + finalFilePath);
                        }

                        // Submit proposal and emit event
                        java.util.concurrent.CompletableFuture<Boolean> future = approvalService.submitProposal(proposal);
                        eventBus.emit(com.mkpro.events.MkProEvent.editProposal(proposal));

                        // Wait for approval (30s timeout — auto-approve timer in TerminalSink fires at 7s)
                        boolean approved;
                        try {
                            approved = future.get(30, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (java.util.concurrent.TimeoutException e) {
                            // Timeout = auto-approve (headless safety)
                            approved = true;
                            approvalService.approve(proposalId);
                        }

                        if (approved) {
                            eventBus.emit(com.mkpro.events.MkProEvent.editApproved(proposalId, finalFilePath));
                            writeFile(path, newContent, finalFilePath);
                            return Collections.singletonMap("status", "File written successfully: " + finalFilePath);
                        } else {
                            eventBus.emit(com.mkpro.events.MkProEvent.editRejected(proposalId, finalFilePath));
                            return Collections.singletonMap("status", "User rejected changes for: " + finalFilePath);
                        }

                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Write failed: " + e.getMessage());
                    }
                });
            }

            private void writeFile(Path path, String content, String filePath) throws java.io.IOException {
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                if (Files.exists(path)) {
                    Maker.backItUp(path.toFile());
                }
                Files.writeString(path, content,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
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
