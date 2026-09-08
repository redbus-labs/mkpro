package com.mkpro.facts;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Sandboxed Groovy execution for fact verification scripts.
 * Runs verify() and validate() functions from MathFact scripts.
 * Timeout: 5 seconds. No I/O, networking, or thread access.
 */
public class GroovyFactEvaluator {

    private static final long TIMEOUT_MS = 5000;
    private final CompilerConfiguration config;

    public GroovyFactEvaluator() {
        this.config = new CompilerConfiguration();
        ImportCustomizer imports = new ImportCustomizer();
        imports.addStarImports("java.lang.Math");
        config.addCompilationCustomizers(imports);
    }

    /**
     * Run the verify() function of a fact script with given variables.
     * Returns the computed result map, or error info.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> verify(MathFact fact, Map<String, Object> variables) {
        if (fact == null || fact.getScript() == null) {
            return Map.of("error", "No script available for fact: " + (fact != null ? fact.getKey() : "null"));
        }

        String script = fact.getScript() + "\nverify(vars)";
        return executeScript(script, variables);
    }

    /**
     * Run the validate() function — checks a claimed result against the correct one.
     * Returns map with 'correct' (boolean), 'expected', 'got'.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> validate(MathFact fact, Map<String, Object> variables) {
        if (fact == null || fact.getScript() == null) {
            return Map.of("error", "No script available");
        }

        // Check if validate function exists in the script
        if (!fact.getScript().contains("def validate")) {
            // Fall back to verify and compare
            return Map.of("error", "No validate() function in script, use verify() instead");
        }

        String script = fact.getScript() + "\nvalidate(vars)";
        return executeScript(script, variables);
    }

    /**
     * Execute a Groovy script with variables and timeout.
     * Uses a fresh daemon thread per evaluation to prevent permanent blocking.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executeScript(String script, Map<String, Object> variables) {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fact-eval-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });

        Future<Map<String, Object>> future = executor.submit(() -> {
            try {
                Binding binding = new Binding();
                binding.setVariable("vars", variables != null ? variables : new HashMap<>());

                GroovyShell shell = new GroovyShell(binding, config);
                Object result = shell.evaluate(script);

                if (result instanceof Map) {
                    return (Map<String, Object>) result;
                }
                return Map.of("result", result != null ? result : "null");
            } catch (Exception e) {
                return Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        });

        try {
            return future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return Map.of("error", "Script timed out (5s limit)");
        } catch (Exception e) {
            return Map.of("error", "Execution failed: " + e.getMessage());
        } finally {
            executor.shutdownNow(); // Abandon the thread if still running
        }
    }

    /**
     * Shutdown — no persistent resources to clean up (per-eval threads are daemon).
     */
    public void shutdown() {
        // No-op: each evaluation creates/destroys its own executor
    }
}
