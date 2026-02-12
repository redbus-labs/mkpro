package com.mkpro.tools;
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

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

import com.google.adk.memory.EmbeddingService;
import com.google.adk.memory.MapDBVectorStore;
import com.google.adk.memory.Vector;

import com.mkpro.IndexingHelper;

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
                        // Ensure embedding is float[] if ADK expects it, or double[]. 
                        // Checking ADK docs/source previously: generateEmbedding returns Single<double[]>.n                        // VectorStore.searchVectors likely takes double[].
                        
                        List<Vector> results = vectorStore.searchTopNVectors( embedding, 0.0, 5); // Top 5, threshold 0.6
                        
                        if (results.isEmpty()) {
                            return Collections.singletonMap("result", "No relevant code found for query: " + query);
                        }
                        
                        StringBuilder sb = new StringBuilder();
                        sb.append("Found ").append(results.size()).append(" relevant snippets:\n\n");
                        
                        for (int i = 0; i < results.size(); i++) {
                            Vector res = results.get(i);
                         
                            // Assuming MemoryEntry content format "FilePath: ... \n Content" or just Content.
                            // We can format it nicely.
                            //sb.append("--- Match ").append(i + 1).append(" (Score: ").append(String.format("%.2f", res.)).append(") ---\n");
                            sb.append(res.getContent()); 
                            sb.append("\n\n");
                        }
                        
                        return Collections.<String, Object>singletonMap("result", sb.toString());
                    });
                  //  .onErrorReturn(e -> Collections.singletonMap("error", "Vector search failed: " + e.getMessage()));
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
                        // Check for headless mode, though on many servers this might just fail or return empty
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
                            // Truncate if too long? For now, let's keep it reasonable.
                            if (text.length() > 20000) {
                                text = text.substring(0, 20000) + "\n...[truncated]";
                            }
                            return ImmutableMap.of(
                                "type", "text",
                                "content", text
                            );
                        } else if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                            BufferedImage image = (BufferedImage) contents.getTransferData(DataFlavor.imageFlavor);
                            
                            // Save to temp file
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

    public static BaseTool createImageCropTool() {
        return new BaseTool(
                "image_crop",
                "Crops an image to the specified dimensions. Useful for focusing on specific UI elements or regions."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "image_path", Schema.builder().type("STRING").description("Path to the image file.").build(),
                                        "x", Schema.builder().type("INTEGER").description("Starting X coordinate.").build(),
                                        "y", Schema.builder().type("INTEGER").description("Starting Y coordinate.").build(),
                                        "width", Schema.builder().type("INTEGER").description("Width of the cropped area.").build(),
                                        "height", Schema.builder().type("INTEGER").description("Height of the cropped area.").build()
                                ))
                                .required(ImmutableList.of("image_path", "x", "y", "width", "height"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String imagePath = (String) args.get("image_path");
                int x = ((Double) args.get("x")).intValue();
                int y = ((Double) args.get("y")).intValue();
                int width = ((Double) args.get("width")).intValue();
                int height = ((Double) args.get("height")).intValue();

                System.out.println(ANSI_BLUE + "[System] Cropping image: " + imagePath + " [" + x + "," + y + " " + width + "x" + height + "]" + ANSI_RESET);

                return Single.fromCallable(() -> {
                    try {
                        File inputFile = new File(imagePath);
                        if (!inputFile.exists()) {
                            return Collections.singletonMap("error", "Image file not found: " + imagePath);
                        }

                        BufferedImage originalImage = ImageIO.read(inputFile);
                        
                        // Bounds check
                        if (x < 0 || y < 0 || x + width > originalImage.getWidth() || y + height > originalImage.getHeight()) {
                             return Collections.singletonMap("error", String.format("Crop coordinates out of bounds. Image size: %dx%d", originalImage.getWidth(), originalImage.getHeight()));
                        }

                        BufferedImage croppedImage = originalImage.getSubimage(x, y, width, height);
                        
                        // Save back to same file
                        String format = imagePath.toLowerCase().endsWith(".png") ? "png" : "jpg";
                        ImageIO.write(croppedImage, format, inputFile);

                        return ImmutableMap.of(
                            "status", "Image cropped successfully.",
                            "image_path", imagePath,
                            "new_size", width + "x" + height
                        );
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Failed to crop image: " + e.getMessage());
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
                                        "file_path", Schema.builder()
                                                .type("STRING")
                                                .description("The path to the file.")
                                                .build(),
                                        "content", Schema.builder()
                                                .type("STRING")
                                                .description("The content to write.")
                                                .build()
                                ))
                                .required(ImmutableList.of("file_path", "content"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String filePath = (String) args.get("file_path");
                String newContent = (String) args.get("content");
                
                return Single.fromCallable(() -> {
                    try {
                        Path path = Paths.get(filePath);
                        String oldContent = "";
                        if (Files.exists(path)) {
                            oldContent = Files.readString(path);
                        } else {
                             System.out.println(ANSI_BLUE + "[CodeEditor] Creating NEW file: " + filePath + ANSI_RESET);
                        }

                        System.out.println(ANSI_BLUE + "\n--- PROPOSED CHANGES FOR: " + filePath + " ---" + ANSI_RESET);
                        
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
                            // Show full content for smaller files (simplified view)
                            // Ideally we would do a line-by-line diff, but for now just showing the new content is safer than a bad diff.
                            // Or better: Show side-by-side or just "Replacing X lines with Y lines".
                            
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
                                } else {
                                    // Context lines (optional, maybe skip for brevity if unchanged)
                                    // System.out.println("  " + oldL);
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
                            Files.writeString(path, newContent, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                            return Collections.singletonMap("status", "File written successfully (Auto-approved): " + filePath);
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
                                Files.writeString(path, newContent, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                                System.out.println(ANSI_GREEN + "File written successfully." + ANSI_RESET);
                                return Collections.singletonMap("status", "File written successfully: " + filePath);
                            } else {
                                System.out.println(ANSI_RED + "Changes rejected by user." + ANSI_RESET);
                                return Collections.singletonMap("status", "User rejected changes for: " + filePath);
                            }
                        }
                        
                        return Collections.singletonMap("status", "No input received. Changes rejected.");

                    } catch (IOException e) {
                        return Collections.singletonMap("error", "Error processing safe write: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createReadFileTool() {
        return new BaseTool(
                "read_file",
                "Reads the content of a file from the local filesystem."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "file_path", Schema.builder() 
                                                .type("STRING")
                                                .description("The path to the file to read.")
                                                .build()
                                ))
                                .required(ImmutableList.of("file_path"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String filePath = (String) args.get("file_path");
                System.out.println(ANSI_BLUE + "[Coder] Reading file: " + filePath + ANSI_RESET);
                try {
                    Path path = Paths.get(filePath);
                    if (!Files.exists(path)) {
                         return Single.just(Collections.singletonMap("error", "File not found: " + filePath));
                    }
                    String content = Files.readString(path);
                    if (content.length() > 10000) {
                        content = content.substring(0, 10000) + "\n...[truncated]";
                    }
                    return Single.just(Collections.singletonMap("content", content));
                } catch (IOException e) {
                    return Single.just(Collections.singletonMap("error", "Error reading file: " + e.getMessage()));
                }
            }
        };
    }

    public static BaseTool createListDirTool() {
        return new BaseTool(
                "list_directory",
                "Lists the files and directories in a given path. Can be recursive."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "dir_path", Schema.builder()
                                                .type("STRING")
                                                .description("The path to the directory to list.")
                                                .build(),
                                        "recursive", Schema.builder()
                                                .type("BOOLEAN")
                                                .description("Whether to list files recursively.")
                                                .build()
                                ))
                                .required(ImmutableList.of("dir_path"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String dirPath = (String) args.get("dir_path");
                Boolean recursive = (Boolean) args.get("recursive");
                if (recursive == null) recursive = false;

                System.out.println(ANSI_BLUE + "[Coder] Listing directory " + (recursive ? "(recursive)" : "") + ": " + dirPath + ANSI_RESET);
                try {
                    Path startPath = Paths.get(dirPath);
                    if (!Files.exists(startPath) || !Files.isDirectory(startPath)) {
                        return Single.just(Collections.singletonMap("error", "Directory not found: " + dirPath));
                    }

                    StringBuilder listing = new StringBuilder();
                    List<String> ignoredDirs = Arrays.asList(".git", "target", "node_modules", ".idea", ".vscode", ".venv", "bin", "obj");

                    if (recursive) {
                        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(0);
                        Files.walk(startPath)
                             .filter(p -> {
                                 // Simple ignore logic
                                 String pathStr = p.toString().toLowerCase();
                                 if (pathStr.endsWith(".db") || pathStr.endsWith(".db-shm") || pathStr.endsWith(".db-wal")) {
                                     return false;
                                 }

                                 for (String ignored : ignoredDirs) {
                                     if (p.toString().contains(java.io.File.separator + ignored + java.io.File.separator) || 
                                         p.toString().endsWith(java.io.File.separator + ignored)) {
                                         return false;
                                     }
                                 }
                                 return true;
                             })
                             .limit(1001) // Limit to 1000 items
                             .forEach(p -> {
                                 if (count.incrementAndGet() > 1000) return;
                                 
                                 Path relative = startPath.relativize(p);
                                 if (relative.toString().isEmpty()) return;
                                 
                                 listing.append(relative.toString().replace("\\", "/"));
                                 if (Files.isDirectory(p)) {
                                     listing.append("/");
                                 }
                                 listing.append("\n");
                             });
                        
                        if (count.get() > 1000) {
                            listing.append("... [truncated after 1000 items]");
                        }
                    } else {
                        Files.list(startPath).forEach(p -> {
                            listing.append(p.getFileName().toString());
                            if (Files.isDirectory(p)) {
                                listing.append("/");
                            }
                            listing.append("\n");
                        });
                    }

                    return Single.just(Collections.singletonMap("listing", listing.toString()));
                } catch (IOException e) {
                    return Single.just(Collections.singletonMap("error", "Error listing directory: " + e.getMessage()));
                }
            }
        };
    }

    public static BaseTool createUrlFetchTool() {
        return new BaseTool(
                "fetch_url",
                "Fetches and extracts text content from a given URL."
        ) {
            private final HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "url", Schema.builder()
                                                .type("STRING")
                                                .description("The full URL to fetch content from.")
                                                .build()
                                ))
                                .required(ImmutableList.of("url"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String url = (String) args.get("url");
                System.out.println(ANSI_BLUE + "[Coordinator] Fetching URL: " + url + ANSI_RESET);
                return Single.fromCallable(() -> {
                    try {
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                                .timeout(Duration.ofSeconds(20))
                                .GET()
                                .build();

                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        
                        if (response.statusCode() >= 400) {
                            return Collections.singletonMap("error", "HTTP Error: " + response.statusCode());
                        }

                        String html = response.body();
                        String text = html.replaceAll("(?s)<style.*?>.*?</style>", "")
                                          .replaceAll("(?s)<script.*?>.*?</script>", "")
                                          .replaceAll("<[^>]+>", " ")
                                          .replaceAll("\\s+", " ")
                                          .trim();
                        
                        if (text.length() > 20000) {
                            text = text.substring(0, 20000) + "\n...[truncated]";
                        }
                        
                        return Collections.singletonMap("content", text);
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Failed to fetch URL: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createGoogleSearchTool() {
        return new BaseTool(
                "google_search",
                "Performs a Google search for the given query and returns the results as text."
        ) {
            private final HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

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
                                                .description("The search query.")
                                                .build()
                                ))
                                .required(ImmutableList.of("query"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String query = (String) args.get("query");
                System.out.println(ANSI_BLUE + "[Search] Googling: " + query + ANSI_RESET);
                return Single.fromCallable(() -> {
                    try {
                        String encodedQuery = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
                        String url = "https://www.google.com/search?q=" + encodedQuery;
                        
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                                .timeout(Duration.ofSeconds(20))
                                .GET()
                                .build();

                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        
                        if (response.statusCode() >= 400) {
                            return Collections.singletonMap("error", "HTTP Error: " + response.statusCode());
                        }

                        String html = response.body();
                        // Basic cleanup to extract readable text
                        String text = html.replaceAll("(?s)<style.*?>.*?</style>", "")
                                          .replaceAll("(?s)<script.*?>.*?</script>", "")
                                          .replaceAll("<[^>]+>", " ")
                                          .replaceAll("\\s+", " ")
                                          .trim();
                        
                        if (text.length() > 20000) {
                            text = text.substring(0, 20000) + "\n...[truncated]";
                        }
                        
                        return Collections.singletonMap("results", text);
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Search failed: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createMultiProjectSearchTool(EmbeddingService embeddingService) {
        return new BaseTool(
                "search_multi_project",
                "Semantically searches across multiple project vector stores. Use this to find code or information from other indexed projects."
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
                                                .description("The search query.")
                                                .build(),
                                        "projects", Schema.builder()
                                                .type("ARRAY")
                                                .items(Schema.builder().type("STRING").build())
                                                .description("Optional list of project names (folder names) to search. If omitted, searches all.")
                                                .build(),
                                        "limit", Schema.builder()
                                                .type("INTEGER")
                                                .description("Max results per project (default 5).")
                                                .build()
                                ))
                                .required(ImmutableList.of("query"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String query = (String) args.get("query");
                List<String> projects = (List<String>) args.get("projects");
                Double limitD = (Double) args.get("limit"); // JSON numbers often come as Double
                int limit = limitD != null ? limitD.intValue() : 5;

                System.out.println(ANSI_BLUE + "[MultiVectorSearch] Searching for: " + query + ANSI_RESET);
                
                return Single.fromCallable(() -> {
                    String result = IndexingHelper.searchMultipleProjects(query, projects, embeddingService, limit);
                    return Collections.singletonMap("result", result);
                });
            }
        };
    }

    public static BaseTool createReadImageTool() {
        return new BaseTool(
                "read_image",
                "Reads an image file and returns its Base64 encoded content. Use this to analyze images."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "file_path", Schema.builder()
                                                .type("STRING")
                                                .description("The path to the image file.")
                                                .build()
                                ))
                                .required(ImmutableList.of("file_path"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String filePath = (String) args.get("file_path");
                System.out.println(ANSI_BLUE + "[Coder] Analyzing image: " + filePath + ANSI_RESET);
                return Single.fromCallable(() -> {
                    try {
                        Path path = Paths.get(filePath);
                        if (!Files.exists(path)) {
                            return Collections.singletonMap("error", "File not found: " + filePath);
                        }
                        byte[] bytes = Files.readAllBytes(path);
                        String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
                        String mimeType = "image/jpeg";
                        if (filePath.toLowerCase().endsWith(".png")) mimeType = "image/png";
                        else if (filePath.toLowerCase().endsWith(".webp")) mimeType = "image/webp";

                        return ImmutableMap.of(
                            "mime_type", mimeType,
                            "data", base64
                        );
                    } catch (IOException e) {
                        return Collections.singletonMap("error", "Error reading image: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createWriteFileTool() {
        return new BaseTool(
                "write_file",
                "Writes content to a file, overwriting it."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                 return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "file_path", Schema.builder()
                                                .type("STRING")
                                                .description("The path to the file.")
                                                .build(),
                                        "content", Schema.builder()
                                                .type("STRING")
                                                .description("The content to write.")
                                                .build()
                                ))
                                .required(ImmutableList.of("file_path", "content"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String filePath = (String) args.get("file_path");
                System.out.println(ANSI_BLUE + "[Coder] Writing file: " + filePath + ANSI_RESET);
                String content = (String) args.get("content");
                return Single.fromCallable(() -> {
                    try {
                        Path path = Paths.get(filePath);
                        if (path.getParent() != null) {
                            Files.createDirectories(path.getParent());
                        }
                        Files.writeString(path, content, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                        return Collections.singletonMap("status", "File written successfully: " + filePath);
                    } catch (IOException e) {
                        return Collections.singletonMap("error", "Error writing file: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createRunShellTool() {
        return new BaseTool(
                "run_shell",
                "Executes a shell command."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "command", Schema.builder()
                                                .type("STRING")
                                                .description("The command to execute.")
                                                .build()
                                ))
                                .required(ImmutableList.of("command"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String command = (String) args.get("command");
                // Security Check
                if (!Maker.isAllowed(command)) {
                    System.out.println(ANSI_RED + "[SysAdmin] BLOCKED: " + command + ANSI_RESET);
                    return Single.just(Collections.singletonMap("error", "Command blocked by security policy: " + command));
                }
                System.out.println(ANSI_BLUE + "[SysAdmin] Executing: " + command + ANSI_RESET);
                return Single.fromCallable(() -> {
                    try {
                        ProcessBuilder pb;
                        String os = System.getProperty("os.name").toLowerCase();
                        if (os.contains("win")) {
                            pb = new ProcessBuilder("cmd.exe", "/c", command);
                        } else {
                            pb = new ProcessBuilder("sh", "-c", command);
                        }
                        
                        // Prevent hanging on interactive input
                        pb.environment().put("PYTHONUNBUFFERED", "1");
                        pb.environment().put("CI", "true"); // Often disables interactive prompts
                        
                        pb.redirectErrorStream(true);
                        Process process = pb.start();
                        
                        // Close stdin immediately to send EOF if script tries to read input
                        process.getOutputStream().close();
                        
                        String output = new String(process.getInputStream().readAllBytes());
                        boolean exited = process.waitFor(10, TimeUnit.SECONDS);
                        if (!exited) {
                             process.destroy();
                             output += "\n[Timeout - process killed]";
                        }
                        int exitCode = exited ? process.exitValue() : -1;

                        return ImmutableMap.of(
                            "exit_code", exitCode,
                            "output", output
                        );
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Command failed: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createGetActionLogsTool(ActionLogger logger) {
        return new BaseTool(
                "get_action_logs",
                "Retrieves the history of user actions and agent responses."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Collections.emptyMap()) // No parameters needed
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    try {
                        StringBuilder logsBuilder = new StringBuilder();
                        for (String log : logger.getLogs()) {
                            logsBuilder.append(log).append("\n");
                        }
                        return Collections.singletonMap("logs", logsBuilder.toString());
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Error retrieving logs: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createSaveMemoryTool(CentralMemory centralMemory) {
        return new BaseTool(
                "save_central_memory",
                "Saves a summary or memory of the current project to the user's central database."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "content", Schema.builder()
                                                .type("STRING")
                                                .description("The summary content to save.")
                                                .build()
                                ))
                                .required(ImmutableList.of("content"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String content = (String) args.get("content");
                String currentPath = Paths.get("").toAbsolutePath().toString();
                System.out.println(ANSI_BLUE + "[Coordinator] Saving to central memory for: " + currentPath + ANSI_RESET);
                return Single.fromCallable(() -> {
                    try {
                        centralMemory.saveMemory(currentPath, content);
                        return Collections.singletonMap("status", "Memory saved successfully for " + currentPath);
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Error saving memory: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createReadMemoryTool(CentralMemory centralMemory) {
        return new BaseTool(
                "read_central_memory",
                "Reads the stored memory for a project. If no path is provided, reads the current project's memory."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "project_path", Schema.builder()
                                                .type("STRING")
                                                .description("Optional absolute path to the project. Defaults to current directory.")
                                                .build()
                                ))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String pathArg = (String) args.get("project_path");
                String currentPath = (pathArg != null && !pathArg.isBlank()) ? pathArg : Paths.get("").toAbsolutePath().toString();
                
                System.out.println(ANSI_BLUE + "[Coordinator] Reading central memory for: " + currentPath + ANSI_RESET);
                return Single.fromCallable(() -> {
                    try {
                        String memory = centralMemory.getMemory(currentPath);
                        if (memory == null) {
                            return Collections.singletonMap("memory", "No memory found for this project.");
                        }
                        return Collections.singletonMap("memory", memory);
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Error reading memory: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createListProjectsTool(CentralMemory centralMemory) {
        return new BaseTool(
                "list_central_memory_projects",
                "Lists all project paths that have data stored in the central memory database."
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
                System.out.println(ANSI_BLUE + "[Coordinator] Listing central memory projects..." + ANSI_RESET);
                return Single.fromCallable(() -> {
                    try {
                        Map<String, String> memories = centralMemory.getAllMemories();
                        if (memories.isEmpty()) {
                             return Collections.singletonMap("projects", "No projects found in central memory.");
                        }
                        return Collections.singletonMap("projects", String.join("\n", memories.keySet()));
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Error listing projects: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createAddGoalTool(CentralMemory centralMemory) {
        return new BaseTool(
                "add_goal",
                "Adds a new goal to the current project's tracking list."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "description", Schema.builder()
                                                .type("STRING")
                                                .description("Description of the goal.")
                                                .build()
                                ))
                                .required(ImmutableList.of("description"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String description = (String) args.get("description");
                String currentPath = Paths.get("").toAbsolutePath().toString();
                System.out.println(ANSI_BLUE + "[GoalTracker] Adding goal: " + description + ANSI_RESET);
                return Single.fromCallable(() -> {
                    try {
                        com.mkpro.models.Goal goal = new com.mkpro.models.Goal(description);
                        centralMemory.addGoal(currentPath, goal);
                        return Collections.singletonMap("status", "Goal added with ID: " + goal.getId());
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Error adding goal: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createListGoalsTool(CentralMemory centralMemory) {
        return new BaseTool(
                "list_goals",
                "Lists all goals for the current project."
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
                String currentPath = Paths.get("").toAbsolutePath().toString();
                System.out.println(ANSI_BLUE + "[GoalTracker] Listing goals..." + ANSI_RESET);
                return Single.fromCallable(() -> {
                    try {
                        List<com.mkpro.models.Goal> goals = centralMemory.getGoals(currentPath);
                        if (goals.isEmpty()) {
                            return Collections.singletonMap("goals", "No goals found.");
                        }
                        StringBuilder sb = new StringBuilder();
                        for (com.mkpro.models.Goal goal : goals) {
                            sb.append(goal.toString()).append("\n");
                        }
                        return Collections.singletonMap("goals", sb.toString());
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Error listing goals: " + e.getMessage());
                    }
                });
            }
        };
    }

    public static BaseTool createUpdateGoalTool(CentralMemory centralMemory) {
        return new BaseTool(
                "update_goal_status",
                "Updates the status of an existing goal."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "goal_id", Schema.builder()
                                                .type("STRING")
                                                .description("The ID of the goal to update.")
                                                .build(),
                                        "status", Schema.builder()
                                                .type("STRING")
                                                .description("New status (PENDING, IN_PROGRESS, COMPLETED, FAILED).")
                                                .build()
                                ))
                                .required(ImmutableList.of("goal_id", "status"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String goalId = (String) args.get("goal_id");
                String statusStr = (String) args.get("status");
                String currentPath = Paths.get("").toAbsolutePath().toString();
                
                System.out.println(ANSI_BLUE + "[GoalTracker] Updating goal " + goalId + " to " + statusStr + ANSI_RESET);
                return Single.fromCallable(() -> {
                    try {
                        List<com.mkpro.models.Goal> goals = centralMemory.getGoals(currentPath);
                        com.mkpro.models.Goal target = null;
                        for (com.mkpro.models.Goal g : goals) {
                            if (g.getId().equals(goalId)) {
                                target = g;
                                break;
                            }
                        }
                        
                        if (target == null) {
                            return Collections.singletonMap("error", "Goal not found with ID: " + goalId);
                        }

                        target.setStatus(com.mkpro.models.Goal.Status.valueOf(statusStr.toUpperCase()));
                        centralMemory.updateGoal(currentPath, target);
                        return Collections.singletonMap("status", "Goal updated successfully.");
                    } catch (IllegalArgumentException e) {
                        return Collections.singletonMap("error", "Invalid status. Use PENDING, IN_PROGRESS, COMPLETED, or FAILED.");
                    } catch (Exception e) {
                        return Collections.singletonMap("error", "Error updating goal: " + e.getMessage());
                    }
                });
            }
        };
    }
}

