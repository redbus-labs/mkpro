package com.mkpro.scripting;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Groovy ScriptEngine — validation, execution, sandboxing.
 */
public class ScriptEngineTest {

    // ==========================================================================
    // Validation: Blocked classes
    // ==========================================================================

    @Test
    void blocksRuntime() {
        String error = ScriptEngine.validate("Runtime.getRuntime().exec('ls')");
        assertNotNull(error);
        assertTrue(error.contains("Runtime"));
    }

    @Test
    void blocksProcessBuilder() {
        String error = ScriptEngine.validate("new ProcessBuilder('ls').start()");
        assertNotNull(error);
        assertTrue(error.contains("ProcessBuilder"));
    }

    @Test
    void blocksSystemExit() {
        String error = ScriptEngine.validate("System.exit(0)");
        assertNotNull(error);
        assertTrue(error.contains("System.exit"));
    }

    @Test
    void blocksNetworking() {
        String error = ScriptEngine.validate("new java.net.URL('http://evil.com').text");
        assertNotNull(error);
        assertTrue(error.contains("java.net"));
    }

    @Test
    void blocksReflection() {
        String error = ScriptEngine.validate("Class.forName('java.lang.Runtime').getDeclaredMethod('exec', String.class)");
        assertNotNull(error);
        assertTrue(error.contains("Class.forName") || error.contains("Runtime"));
    }

    @Test
    void blocksThreadCreation() {
        String error = ScriptEngine.validate("new Thread({ println 'hi' }).start()");
        // "Thread.start" should be caught
        assertNotNull(error);
    }

    @Test
    void blocksMkProInternals() {
        String error = ScriptEngine.validate("com.mkpro.CentralMemory.getInstance()");
        assertNotNull(error);
        assertTrue(error.contains("com.mkpro"));
    }

    @Test
    void blocksSQL() {
        String error = ScriptEngine.validate("import java.sql.DriverManager; DriverManager.getConnection('jdbc:h2:mem:')");
        assertNotNull(error);
        assertTrue(error.contains("java.sql"));
    }

    // ==========================================================================
    // Validation: Allowed code
    // ==========================================================================

    @Test
    void allowsCoreJavaCollections() {
        assertNull(ScriptEngine.validate("def list = [1, 2, 3]; println list.sort()"));
    }

    @Test
    void allowsMath() {
        assertNull(ScriptEngine.validate("println Math.sqrt(144)"));
    }

    @Test
    void allowsStreams() {
        assertNull(ScriptEngine.validate("def result = [1,2,3,4,5].stream().filter{it > 2}.toList(); println result"));
    }

    @Test
    void allowsRegex() {
        assertNull(ScriptEngine.validate("def m = 'hello123' =~ /\\d+/; println m[0]"));
    }

    @Test
    void allowsFileRead() {
        // File reading is allowed (project-level scripts need to read data)
        assertNull(ScriptEngine.validate("def text = new File('test.txt').text"));
    }

    @Test
    void allowsDatetime() {
        assertNull(ScriptEngine.validate("import java.time.*; println LocalDateTime.now()"));
    }

    // ==========================================================================
    // Execution: Basic scripts
    // ==========================================================================

    @Test
    void executesSimpleScript() {
        var result = ScriptEngine.execute("println 'hello world'", Map.of());
        assertTrue(result.success);
        assertTrue(result.output.contains("hello world"));
    }

    @Test
    void executesWithArguments() {
        var result = ScriptEngine.execute("println \"Hello ${name}!\"", Map.of("name", "mkpro"));
        assertTrue(result.success);
        assertTrue(result.output.contains("Hello mkpro!"));
    }

    @Test
    void executesAlgorithm() {
        String code = """
            def numbers = [5, 3, 8, 1, 9, 2, 7, 4, 6]
            def sorted = numbers.sort()
            println sorted.join(', ')
            return sorted.size()
            """;
        var result = ScriptEngine.execute(code, Map.of());
        assertTrue(result.success);
        assertTrue(result.output.contains("1, 2, 3, 4, 5, 6, 7, 8, 9"));
        assertEquals(9, result.returnValue);
    }

    @Test
    void executesMapReduce() {
        String code = """
            def words = text.toLowerCase().split(/\\W+/)
            def freq = words.groupBy{it}.collectEntries{[it.key, it.value.size()]}
            freq.sort{-it.value}.take(3).each{ println "${it.key}: ${it.value}" }
            """;
        var result = ScriptEngine.execute(code, Map.of("text", "the cat sat on the mat the cat"));
        assertTrue(result.success);
        assertTrue(result.output.contains("the: 3"));
        assertTrue(result.output.contains("cat: 2"));
    }

    @Test
    void capturesReturnValue() {
        var result = ScriptEngine.execute("return 42 * 2", Map.of());
        assertTrue(result.success);
        assertEquals(84, result.returnValue);
    }

    // ==========================================================================
    // Execution: Error handling
    // ==========================================================================

    @Test
    void handlesRuntimeError() {
        var result = ScriptEngine.execute("def x = 1 / 0", Map.of());
        assertFalse(result.success);
        assertTrue(result.error.contains("ArithmeticException") || result.error.contains("Division"));
    }

    @Test
    void handlesNullPointer() {
        var result = ScriptEngine.execute("String s = null; println s.length()", Map.of());
        assertFalse(result.success);
        assertTrue(result.error.contains("NullPointer") || result.error.contains("Cannot"));
    }

    @Test
    void handlesTimeout() {
        var result = ScriptEngine.execute("while(true) { }", Map.of(), 2);
        assertFalse(result.success);
        assertTrue(result.error.contains("timed out"));
    }

    @Test
    void rejectsEmptyScript() {
        var result = ScriptEngine.execute("", Map.of());
        assertFalse(result.success);
        assertTrue(result.error.contains("empty"));
    }

    @Test
    void rejectsSyntaxError() {
        var result = ScriptEngine.execute("def x = {{{", Map.of());
        assertFalse(result.success);
        assertTrue(result.error.contains("Compilation") || result.error.contains("syntax"));
    }

    // ==========================================================================
    // Execution: Core Java interop
    // ==========================================================================

    @Test
    void usesCollections() {
        String code = """
            def map = new TreeMap()
            map.put('b', 2)
            map.put('a', 1)
            map.put('c', 3)
            println map.keySet().join(', ')
            """;
        var result = ScriptEngine.execute(code, Map.of());
        assertTrue(result.success);
        assertTrue(result.output.contains("a, b, c"));
    }

    @Test
    void usesBigDecimal() {
        String code = "println new BigDecimal('0.1').add(new BigDecimal('0.2'))";
        var result = ScriptEngine.execute(code, Map.of());
        assertTrue(result.success);
        assertTrue(result.output.contains("0.3"));
    }

    @Test
    void usesStringFormatting() {
        String code = "printf('%s has %d items%n', 'cart', 5)";
        var result = ScriptEngine.execute(code, Map.of());
        assertTrue(result.success);
        assertTrue(result.output.contains("cart has 5 items"));
    }
}
