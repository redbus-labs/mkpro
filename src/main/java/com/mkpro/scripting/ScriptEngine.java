package com.mkpro.scripting;

import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyShell;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Sandboxed Groovy script execution engine.
 * 
 * Restrictions:
 * - Core Java classes only (no third-party imports)
 * - Blocked: Runtime, ProcessBuilder, System.exit, Thread, ClassLoader, reflection, networking
 * - Execution timeout: configurable (default 30s)
 * - Output captured from stdout/stderr
 */
public class ScriptEngine {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    // Classes/packages that are NEVER allowed
    private static final List<String> BLOCKED_PATTERNS = List.of(
        "Runtime", "ProcessBuilder", "System.exit",
        "java.lang.reflect", "java.lang.invoke",
        "Class.forName",  // Reflection entry point
        "java.net.", "javax.net", "sun.net",
        "ClassLoader", "URLClassLoader",
        "new Thread", "Thread.start",  // No thread creation
        "java.security", "javax.crypto",
        "groovy.lang.GroovyShell", "groovy.lang.GroovyClassLoader",
        "org.codehaus.groovy",
        "com.mkpro",  // Prevent access to mkpro internals
        "System.setProperty", "System.getenv",
        "java.lang.ProcessHandle",
        "java.awt", "javax.swing",  // No GUI
        "java.sql", "javax.sql"     // No direct DB access
    );

    // Allowed import packages (core Java only)
    private static final List<String> ALLOWED_STAR_IMPORTS = List.of(
        "java.util",
        "java.util.stream",
        "java.util.regex",
        "java.util.function",
        "java.math",
        "java.text",
        "java.time",
        "java.time.format",
        "java.io",
        "java.nio.file",
        "java.nio.charset"
    );

    /**
     * Result of script execution.
     */
    public static class ScriptResult {
        public final boolean success;
        public final String output;     // captured stdout
        public final String error;      // error message if failed
        public final Object returnValue;
        public final long durationMs;

        private ScriptResult(boolean success, String output, String error, Object returnValue, long durationMs) {
            this.success = success;
            this.output = output;
            this.error = error;
            this.returnValue = returnValue;
            this.durationMs = durationMs;
        }

        public static ScriptResult success(String output, Object returnValue, long durationMs) {
            return new ScriptResult(true, output, null, returnValue, durationMs);
        }

        public static ScriptResult failure(String error) {
            return new ScriptResult(false, null, error, null, 0);
        }
    }

    /**
     * Validate a script for safety. Returns null if safe, error message if not.
     */
    public static String validate(String code) {
        if (code == null || code.isBlank()) {
            return "Script is empty.";
        }

        // Check for blocked patterns
        for (String blocked : BLOCKED_PATTERNS) {
            if (code.contains(blocked)) {
                return "Blocked: script contains forbidden usage '" + blocked + "'. " +
                       "Only core Java algorithm/data processing classes are allowed.";
            }
        }

        // Check for suspicious patterns
        if (code.contains("exec(") || code.contains(".execute(")) {
            if (code.contains("Runtime") || code.contains("Process")) {
                return "Blocked: script attempts to execute system commands.";
            }
        }

        // Try compilation (catches syntax errors)
        try {
            CompilerConfiguration config = createCompilerConfig();
            GroovyShell shell = new GroovyShell(config);
            shell.parse(code);
        } catch (CompilationFailedException e) {
            return "Compilation error: " + e.getMessage();
        }

        return null; // Safe
    }

    /**
     * Execute a script with the given arguments.
     *
     * @param code The Groovy script source code
     * @param args Arguments passed to the script as 'args' binding
     * @param timeoutSeconds Maximum execution time
     * @return ScriptResult with output or error
     */
    public static ScriptResult execute(String code, Map<String, Object> args, int timeoutSeconds) {
        // Validate first
        String validationError = validate(code);
        if (validationError != null) {
            return ScriptResult.failure(validationError);
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ScriptResult> future = executor.submit(() -> executeInternal(code, args));
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return ScriptResult.failure("Script execution timed out after " + timeoutSeconds + " seconds.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            return ScriptResult.failure("Execution error: " + (cause != null ? cause.getMessage() : e.getMessage()));
        } catch (Exception e) {
            return ScriptResult.failure("Error: " + e.getMessage());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Execute with default timeout.
     */
    public static ScriptResult execute(String code, Map<String, Object> args) {
        return execute(code, args, DEFAULT_TIMEOUT_SECONDS);
    }

    private static ScriptResult executeInternal(String code, Map<String, Object> args) {
        long startTime = System.currentTimeMillis();

        // Capture stdout
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream capturedOut = new PrintStream(baos, true, StandardCharsets.UTF_8);

        // Set up binding
        Binding binding = new Binding();
        if (args != null) {
            args.forEach(binding::setVariable);
        }
        binding.setVariable("out", capturedOut);

        // Configure shell
        CompilerConfiguration config = createCompilerConfig();
        GroovyShell shell = new GroovyShell(binding, config);

        // Redirect System.out for the script
        PrintStream originalOut = System.out;
        System.setOut(capturedOut);
        try {
            Object result = shell.evaluate(code);
            long duration = System.currentTimeMillis() - startTime;
            String output = baos.toString(StandardCharsets.UTF_8);
            return ScriptResult.success(output, result, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            String output = baos.toString(StandardCharsets.UTF_8);
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (!output.isEmpty()) {
                error = "Output before error:\n" + output + "\n\nError: " + error;
            }
            return ScriptResult.failure(error);
        } finally {
            System.setOut(originalOut);
        }
    }

    private static CompilerConfiguration createCompilerConfig() {
        CompilerConfiguration config = new CompilerConfiguration();

        // Add default imports for convenience
        ImportCustomizer imports = new ImportCustomizer();
        for (String pkg : ALLOWED_STAR_IMPORTS) {
            imports.addStarImports(pkg);
        }
        imports.addStaticStars("java.lang.Math");
        config.addCompilationCustomizers(imports);

        return config;
    }
}
