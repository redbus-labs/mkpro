package com.mkpro.tools;

import com.mkpro.security.PathValidator;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for read_file (line-range pagination) and list_dir (recursive + pagination).
 */
public class FileToolsTest {

    private static Path testRoot;
    private com.google.adk.tools.BaseTool readFile;
    private com.google.adk.tools.BaseTool listDir;

    @BeforeAll
    static void createTestProject() throws IOException {
        testRoot = Files.createTempDirectory("filetools-test-");
        // Initialize PathValidator with test root
        PathValidator.initialize(testRoot, null);

        // Create a mock project structure
        Files.createDirectories(testRoot.resolve("src/main/java/com/example"));
        Files.createDirectories(testRoot.resolve("src/test/java"));
        Files.createDirectories(testRoot.resolve("docs"));
        Files.createDirectories(testRoot.resolve("node_modules/pkg")); // should be excluded

        // Create files with known content
        StringBuilder bigFile = new StringBuilder();
        for (int i = 1; i <= 200; i++) {
            bigFile.append("Line ").append(i).append(": content here\n");
        }
        Files.writeString(testRoot.resolve("src/main/java/com/example/App.java"), bigFile.toString());
        Files.writeString(testRoot.resolve("src/main/java/com/example/Utils.java"), "public class Utils {}");
        Files.writeString(testRoot.resolve("docs/README.md"), "# Project\nDescription here.");
        Files.writeString(testRoot.resolve("pom.xml"), "<project/>");
        Files.writeString(testRoot.resolve("node_modules/pkg/index.js"), "// noise");
    }

    @AfterAll
    static void cleanup() {
        try {
            Files.walk(testRoot).sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }

    @BeforeEach
    void setUp() {
        PathValidator.initialize(testRoot, null);
        readFile = MkProTools.createReadFileTool();
        listDir = MkProTools.createListDirTool();
    }

    // ==========================================================================
    // read_file tests
    // ==========================================================================

    @Test
    void readFileReturnsContent() {
        Map<String, Object> result = readFile.runAsync(
            Map.of("path", testRoot.resolve("pom.xml").toString()), null).blockingGet();
        assertEquals("<project/>", result.get("content"));
    }

    @Test
    void readFileReturnsTotalLines() {
        Map<String, Object> result = readFile.runAsync(
            Map.of("path", testRoot.resolve("src/main/java/com/example/App.java").toString()), null).blockingGet();
        assertEquals(200, result.get("total_lines"));
    }

    @Test
    void readFileDefaultLimitsTo500Lines() {
        Map<String, Object> result = readFile.runAsync(
            Map.of("path", testRoot.resolve("src/main/java/com/example/App.java").toString()), null).blockingGet();
        String content = (String) result.get("content");
        // 200 lines < 500 limit, so all should be returned
        assertTrue(content.contains("Line 1:"));
        assertTrue(content.contains("Line 200:"));
    }

    @Test
    void readFileWithStartLine() {
        Map<String, Object> result = readFile.runAsync(
            Map.of("path", testRoot.resolve("src/main/java/com/example/App.java").toString(),
                   "start_line", 50), null).blockingGet();
        String content = (String) result.get("content");
        assertTrue(content.startsWith("Line 50:"));
        assertFalse(content.contains("Line 49:"));
    }

    @Test
    void readFileWithEndLine() {
        Map<String, Object> result = readFile.runAsync(
            Map.of("path", testRoot.resolve("src/main/java/com/example/App.java").toString(),
                   "start_line", 10, "end_line", 15), null).blockingGet();
        String content = (String) result.get("content");
        assertTrue(content.contains("Line 10:"));
        assertTrue(content.contains("Line 15:"));
        assertFalse(content.contains("Line 16:"));
        assertEquals("10-15", result.get("showing_lines"));
    }

    @Test
    void readFileHasMoreIndicator() {
        Map<String, Object> result = readFile.runAsync(
            Map.of("path", testRoot.resolve("src/main/java/com/example/App.java").toString(),
                   "start_line", 1, "end_line", 50), null).blockingGet();
        assertEquals(true, result.get("has_more"));
        assertEquals(51, result.get("next_start_line"));
    }

    @Test
    void readFileNoHasMoreAtEnd() {
        Map<String, Object> result = readFile.runAsync(
            Map.of("path", testRoot.resolve("src/main/java/com/example/App.java").toString(),
                   "start_line", 190, "end_line", 200), null).blockingGet();
        assertNull(result.get("has_more"));
    }

    @Test
    void readFileBlockedOutsideRoot() {
        Map<String, Object> result = readFile.runAsync(
            Map.of("path", "C:/Windows/System32/cmd.exe"), null).blockingGet();
        assertNotNull(result.get("error"));
    }

    // ==========================================================================
    // list_dir tests
    // ==========================================================================

    @Test
    void listDirReturnsEntries() {
        Map<String, Object> result = listDir.runAsync(
            Map.of("path", testRoot.toString()), null).blockingGet();
        List<String> files = (List<String>) result.get("files");
        assertNotNull(files);
        assertFalse(files.isEmpty());
        assertTrue(files.contains("pom.xml"));
        assertTrue(files.contains("src/"));
    }

    @Test
    void listDirExcludesNodeModules() {
        Map<String, Object> result = listDir.runAsync(
            Map.of("path", testRoot.toString(), "recursive", true, "depth", 6), null).blockingGet();
        List<String> files = (List<String>) result.get("files");
        // No entries should contain node_modules
        assertTrue(files.stream().noneMatch(f -> f.contains("node_modules")),
            "Should exclude node_modules. Got: " + files);
    }

    @Test
    void listDirRecursive() {
        Map<String, Object> result = listDir.runAsync(
            Map.of("path", testRoot.toString(), "recursive", true, "depth", 6), null).blockingGet();
        List<String> files = (List<String>) result.get("files");
        // Should find nested files
        assertTrue(files.stream().anyMatch(f -> f.contains("App.java")),
            "Should find App.java recursively. Got: " + files);
    }

    @Test
    void listDirNonRecursiveOnlyTopLevel() {
        Map<String, Object> result = listDir.runAsync(
            Map.of("path", testRoot.toString()), null).blockingGet();
        List<String> files = (List<String>) result.get("files");
        // Top-level entries should not contain path separators (except trailing / for dirs)
        assertTrue(files.stream().allMatch(f -> {
            String withoutTrailingSlash = f.endsWith("/") ? f.substring(0, f.length() - 1) : f;
            return !withoutTrailingSlash.contains("/");
        }), "Non-recursive should only show top-level entries. Got: " + files);
    }

    @Test
    void listDirPagination() {
        Map<String, Object> result = listDir.runAsync(
            Map.of("path", testRoot.toString(), "recursive", true, "depth", 5, "limit", 3, "offset", 0), null).blockingGet();
        List<String> files = (List<String>) result.get("files");
        assertEquals(3, files.size());
        assertEquals(true, result.get("has_more"));
        assertEquals(3, result.get("next_offset"));
    }

    @Test
    void listDirPaginationOffset() {
        // Get first page
        Map<String, Object> page1 = listDir.runAsync(
            Map.of("path", testRoot.toString(), "recursive", true, "depth", 5, "limit", 2, "offset", 0), null).blockingGet();
        List<String> files1 = (List<String>) page1.get("files");

        // Get second page
        Map<String, Object> page2 = listDir.runAsync(
            Map.of("path", testRoot.toString(), "recursive", true, "depth", 5, "limit", 2, "offset", 2), null).blockingGet();
        List<String> files2 = (List<String>) page2.get("files");

        // Pages should not overlap
        for (String f : files2) {
            assertFalse(files1.contains(f), "Page 2 should not contain: " + f);
        }
    }

    @Test
    void listDirReturnsTotal() {
        Map<String, Object> result = listDir.runAsync(
            Map.of("path", testRoot.toString(), "recursive", true, "depth", 5), null).blockingGet();
        long total = ((Number) result.get("total")).longValue();
        assertTrue(total > 0);
    }

    @Test
    void listDirDirectoriesMarkedWithSlash() {
        Map<String, Object> result = listDir.runAsync(
            Map.of("path", testRoot.toString()), null).blockingGet();
        List<String> files = (List<String>) result.get("files");
        assertTrue(files.stream().anyMatch(f -> f.equals("src/")));
        assertTrue(files.stream().anyMatch(f -> f.equals("docs/")));
    }

    @Test
    void listDirBlockedOutsideRoot() {
        Map<String, Object> result = listDir.runAsync(
            Map.of("path", "C:/Windows"), null).blockingGet();
        assertNotNull(result.get("error"));
    }

    @Test
    void listDirNonDirectoryReturnsError() {
        Map<String, Object> result = listDir.runAsync(
            Map.of("path", testRoot.resolve("pom.xml").toString()), null).blockingGet();
        String error = (String) result.get("error");
        assertNotNull(error);
        assertTrue(error.contains("Not a directory"));
    }
}
