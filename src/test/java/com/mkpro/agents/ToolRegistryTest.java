package com.mkpro.agents;

import com.google.adk.tools.BaseTool;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ToolRegistry — tool resolution from declarative names.
 */
public class ToolRegistryTest {

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        // Create with null vector store/embedding (tools that need them will be empty)
        registry = new ToolRegistry(null, null);
    }

    @Test
    void resolveKnownTool() {
        List<BaseTool> tools = registry.resolve(List.of("clipboard"));
        assertNotNull(tools);
        assertFalse(tools.isEmpty());
    }

    @Test
    void resolveMultipleTools() {
        List<BaseTool> tools = registry.resolve(List.of("clipboard", "shell"));
        assertNotNull(tools);
        assertTrue(tools.size() >= 2);
    }

    @Test
    void resolveUnknownToolSkipped() {
        List<BaseTool> tools = registry.resolve(List.of("nonexistent_tool_xyz"));
        assertNotNull(tools);
        assertTrue(tools.isEmpty());
    }

    @Test
    void resolveNullListReturnsEmpty() {
        List<BaseTool> tools = registry.resolve(null);
        assertNotNull(tools);
        assertTrue(tools.isEmpty());
    }

    @Test
    void resolveEmptyListReturnsEmpty() {
        List<BaseTool> tools = registry.resolve(List.of());
        assertNotNull(tools);
        assertTrue(tools.isEmpty());
    }

    @Test
    void resolveMixedKnownAndUnknown() {
        List<BaseTool> tools = registry.resolve(List.of("clipboard", "unknown_tool", "shell"));
        assertNotNull(tools);
        // Should resolve clipboard and shell but skip unknown
        assertTrue(tools.size() >= 2);
    }

    @Test
    void resolveFileRead() {
        List<BaseTool> tools = registry.resolve(List.of("file_read"));
        assertNotNull(tools);
        assertFalse(tools.isEmpty());
    }

    @Test
    void resolveFileWrite() {
        List<BaseTool> tools = registry.resolve(List.of("file_write"));
        assertNotNull(tools);
        assertFalse(tools.isEmpty());
    }

    @Test
    void resolveSafeWrite() {
        List<BaseTool> tools = registry.resolve(List.of("safe_write"));
        assertNotNull(tools);
        assertFalse(tools.isEmpty());
    }

    @Test
    void resolveShell() {
        List<BaseTool> tools = registry.resolve(List.of("shell"));
        assertNotNull(tools);
        assertFalse(tools.isEmpty());
    }

    @Test
    void resolveImage() {
        List<BaseTool> tools = registry.resolve(List.of("image"));
        assertNotNull(tools);
        assertFalse(tools.isEmpty());
    }

    @Test
    void resolveScripting() {
        List<BaseTool> tools = registry.resolve(List.of("scripting"));
        assertNotNull(tools);
        assertFalse(tools.isEmpty());
    }
}
