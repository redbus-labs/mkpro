package com.mkpro.tools;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Single;
import com.mkpro.ActionLogger;
import com.mkpro.CentralMemory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class MkProTools {

    public static final String ANSI_RESET = "\u001b[0m";
    public static final String ANSI_BLUE = "\u001b[34m";

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
                "Lists the files and directories in a given path."
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
                                                .build()
                                ))
                                .required(ImmutableList.of("dir_path"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                String dirPath = (String) args.get("dir_path");
                System.out.println(ANSI_BLUE + "[Coder] Listing directory: " + dirPath + ANSI_RESET);
                try {
                     Path path = Paths.get(dirPath);
                    if (!Files.exists(path) || !Files.isDirectory(path)) {
                         return Single.just(Collections.singletonMap("error", "Directory not found: " + dirPath));
                    }
                    
                    StringBuilder listing = new StringBuilder();
                    Files.list(path).forEach(p -> {
                        listing.append(p.getFileName().toString());
                        if (Files.isDirectory(p)) {
                            listing.append("/");
                        }
                        listing.append("\n");
                    });
                    
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
                        pb.redirectErrorStream(true);
                        Process process = pb.start();
                        
                        String output = new String(process.getInputStream().readAllBytes());
                        boolean exited = process.waitFor(10, TimeUnit.SECONDS);
                        if (!exited) {
                             process.destroy();
                             output += "\n[Timeout]";
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
}
