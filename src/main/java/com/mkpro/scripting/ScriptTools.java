package com.mkpro.scripting;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ADK tools for the Groovy script engine.
 * 
 * Tools:
 * - execute_script: run a named script with arguments
 * - create_script: write and save a new Groovy script
 * - list_scripts: show all available scripts in the repository
 * 
 * Scripts are restricted to core Java libraries only (no third-party deps) and are strictly read-only.
 * Designed for algorithm/data processing tasks: sorting, filtering, parsing,
 * transformations, calculations, text processing, and inspection.
 */
public class ScriptTools {

    private static ScriptRepository repository;

    private static final List<String> BLOCKED_FILE_WRITE_PATTERNS = List.of(
        "Files.write", "Files.writeString", "Files.delete", "Files.move", "Files.copy",
        "FileWriter", "FileOutputStream", "PrintWriter", "RandomAccessFile",
        ".delete()", ".deleteDir(", ".setText(", ".setBytes("
    );
    private static final java.util.regex.Pattern GROOVY_FILE_ASSIGN_PATTERN = java.util.regex.Pattern.compile("\\.(text|bytes)\\s*=");

    public static String validateReadOnlyForAgent(String code) {
        for (String writePattern : BLOCKED_FILE_WRITE_PATTERNS) {
            if (code.contains(writePattern)) {
                return "Blocked: Groovy scripts are read-only and cannot modify or delete files.";
            }
        }
        if (GROOVY_FILE_ASSIGN_PATTERN.matcher(code).find()) {
            return "Blocked: Groovy scripts are read-only and cannot modify or delete files.";
        }
        return null;
    }

    /**
     * Initialize with a CentralMemory-backed repository.
     */
    public static void init(com.mkpro.CentralMemory centralMemory) {
        repository = new ScriptRepository(centralMemory);
    }

    public static ScriptRepository getRepository() {
        return repository;
    }

    /**
     * Tool: execute_script
     * Runs a named script from the repository, or executes inline code.
     */
    public static BaseTool executeScript() {
        return new BaseTool("execute_script",
            "Execute a Groovy script by name (from repository) or inline code. " +
            "Scripts can only use core Java libraries (java.util, java.io, java.math, java.time, etc). " +
            "No third-party libraries. Strictly read-only: designed for algorithms, data transformation, text processing, computation, and inspection. " +
            "Cannot write, modify, or delete files (use replace_in_file, safe_write_file, or write_file instead).") {

            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                    .name(name())
                    .description(description())
                    .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(Map.of(
                            "name", Schema.builder().type("STRING")
                                .description("Name of saved script to execute. Omit if using inline code.").build(),
                            "code", Schema.builder().type("STRING")
                                .description("Inline Groovy code to execute. Omit if using a saved script name.").build(),
                            "args", Schema.builder().type("STRING")
                                .description("JSON string of arguments to pass to the script. Available as variables in the script.").build()
                        ))
                        .build())
                    .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    if (repository == null) {
                        return Collections.singletonMap("error", (Object) "Script engine not initialized.");
                    }

                    String name = args.get("name") != null ? args.get("name").toString() : null;
                    String code = args.get("code") != null ? args.get("code").toString() : null;
                    String argsJson = args.get("args") != null ? args.get("args").toString() : null;

                    // Resolve code
                    if (name != null && !name.isEmpty()) {
                        ScriptRepository.ScriptEntry entry = repository.load(name);
                        if (entry == null) {
                            return Collections.singletonMap("error", (Object) ("Script '" + name + "' not found. Use list_scripts to see available scripts."));
                        }
                        code = entry.code;
                        repository.recordUsage(name);
                    }

                    if (code == null || code.isBlank()) {
                        return Collections.singletonMap("error", (Object) "No script name or code provided.");
                    }

                    // Security: Read-only validation
                    String readOnlyError = validateReadOnlyForAgent(code);
                    if (readOnlyError != null) {
                        return Collections.singletonMap("error", (Object) readOnlyError);
                    }

                    // Parse args
                    Map<String, Object> scriptArgs = new HashMap<>();
                    if (argsJson != null && !argsJson.isBlank()) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                                .readValue(argsJson, Map.class);
                            scriptArgs.putAll(parsed);
                        } catch (Exception e) {
                            return Collections.singletonMap("error", (Object) ("Invalid args JSON: " + e.getMessage()));
                        }
                    }

                    // Execute
                    ScriptEngine.ScriptResult result = ScriptEngine.execute(code, scriptArgs);

                    Map<String, Object> response = new LinkedHashMap<>();
                    if (result.success) {
                        response.put("status", "success");
                        response.put("output", result.output != null && !result.output.isEmpty() ? result.output : "(no output)");
                        if (result.returnValue != null) {
                            response.put("returnValue", result.returnValue.toString());
                        }
                        response.put("durationMs", result.durationMs);
                    } else {
                        response.put("status", "error");
                        response.put("error", result.error);
                    }
                    return response;
                });
            }
        };
    }

    /**
     * Tool: create_script
     * Creates and saves a new Groovy script to the repository.
     */
    public static BaseTool createScript() {
        return new BaseTool("create_script",
            "Create and save a reusable Groovy script to the repository. " +
            "Scripts must only use core Java classes (java.util, java.io, java.math, java.time, java.nio, java.text, java.util.regex). " +
            "Blocked: Runtime, ProcessBuilder, System.exit, Thread, ClassLoader, reflection, networking, SQL, and all file writing/modification/deletion. " +
            "Strictly read-only — good for: sorting, filtering, parsing, formatting, calculations, text processing, and read-only file inspection.") {

            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                    .name(name())
                    .description(description())
                    .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(Map.of(
                            "name", Schema.builder().type("STRING")
                                .description("Script name (alphanumeric + underscores, e.g. 'word_frequency').").build(),
                            "code", Schema.builder().type("STRING")
                                .description("The Groovy source code. Use println for output. Args available as variables.").build(),
                            "description", Schema.builder().type("STRING")
                                .description("Brief description of what the script does.").build()
                        ))
                        .required(List.of("name", "code", "description"))
                        .build())
                    .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    if (repository == null) {
                        return Collections.singletonMap("error", (Object) "Script engine not initialized.");
                    }

                    String name = args.get("name") != null ? args.get("name").toString() : null;
                    String code = args.get("code") != null ? args.get("code").toString() : null;
                    String description = args.get("description") != null ? args.get("description").toString() : "";

                    // Security: Read-only validation
                    String readOnlyError = validateReadOnlyForAgent(code);
                    if (readOnlyError != null) {
                        return Collections.singletonMap("error", (Object) readOnlyError);
                    }

                    String error = repository.save(name, code, description);
                    if (error != null) {
                        return Collections.singletonMap("error", (Object) error);
                    }

                    return Map.of(
                        "status", (Object) "success",
                        "message", "Script '" + name + "' saved successfully. Use execute_script(name: '" + name + "') to run it."
                    );
                });
            }
        };
    }

    /**
     * Tool: list_scripts
     * Lists all scripts in the repository with descriptions.
     */
    public static BaseTool listScripts() {
        return new BaseTool("list_scripts",
            "List all saved Groovy scripts in the repository with their descriptions and usage stats.") {

            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                    .name(name())
                    .description(description())
                    .parameters(Schema.builder().type("OBJECT").properties(Map.of()).build())
                    .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    if (repository == null) {
                        return Collections.singletonMap("error", (Object) "Script engine not initialized.");
                    }

                    List<ScriptRepository.ScriptEntry> scripts = repository.listAll();
                    if (scripts.isEmpty()) {
                        return Collections.singletonMap("result", (Object) "No scripts in repository. Use create_script to add one.");
                    }

                    String listing = scripts.stream()
                        .map(s -> String.format("• %s — %s (used %d times)", s.name, s.description, s.usageCount))
                        .collect(Collectors.joining("\n"));

                    return Map.of(
                        "count", (Object) scripts.size(),
                        "scripts", listing
                    );
                });
            }
        };
    }
}
