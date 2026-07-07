package com.mkpro.agents;

import com.google.adk.memory.EmbeddingService;
import com.google.adk.memory.MapDBVectorStore;
import com.google.adk.tools.BaseTool;
import com.mkpro.tools.*;

import java.util.*;

/**
 * ToolRegistry provides a declarative mapping from tool names (strings in YAML)
 * to actual BaseTool instances. Tools are created lazily and cached.
 *
 * Supported tool names:
 *   file_read, file_write, safe_write, clipboard, shell, image,
 *   codebase_search, multi_project_search, mcp_scan, graph_memory,
 *   fetch_url, stats, selenium
 */
public class ToolRegistry {

    private final Map<String, List<BaseTool>> toolCache = new LinkedHashMap<>();
    private final MapDBVectorStore vectorStore;
    private final EmbeddingService embeddingService;

    public ToolRegistry(MapDBVectorStore vectorStore, EmbeddingService embeddingService) {
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
        initializeTools();
    }

    private void initializeTools() {
        // File operations
        toolCache.put("file_read", List.of(FileSystemTools.create()));
        toolCache.put("file_write", List.of(MkProTools.createWriteFileTool()));
        toolCache.put("safe_write", List.of(MkProTools.createSafeWriteFileTool()));

        // Clipboard
        toolCache.put("clipboard", List.of(ClipboardTools.create()));

        // Shell execution
        toolCache.put("shell", List.of(ShellTools.create()));

        // Image analysis
        toolCache.put("image", List.of(ImageTools.create()));

        // Codebase search (vector embeddings)
        toolCache.put("codebase_search", List.of(CodebaseSearchTools.create(vectorStore, embeddingService)));

        // Multi-project search
        toolCache.put("multi_project_search", List.of(MultiProjectSearchTools.create(embeddingService, vectorStore)));

        // MCP server scanning
        toolCache.put("mcp_scan", List.of(
            McpServerConnectTools.createScanProjectTool(),
            McpServerConnectTools.createSaveComponentTool()
        ));

        // Graph memory (memorize, recall, visualize)
        toolCache.put("graph_memory", List.of(
            GraphMemoryTools.memorizeFact(),
            GraphMemoryTools.recallMemory(),
            GraphMemoryTools.visualizeGraph()
        ));

        // URL fetching
        toolCache.put("fetch_url", List.of(FetchUrlTools.create()));

        // Session stats
        toolCache.put("stats", List.of(StatsTools.createGetSessionStatsTool()));

        // Selenium browser tools
        try {
            toolCache.put("selenium", List.of(
                SeleniumTools.createNavigateTool(),
                SeleniumTools.createClickTool(),
                SeleniumTools.createTypeTool(),
                SeleniumTools.createScreenshotTool(),
                SeleniumTools.createGetHtmlTool(),
                SeleniumTools.createCloseTool()
            ));
        } catch (Exception e) {
            // Selenium driver setup may fail — register empty list
            toolCache.put("selenium", Collections.emptyList());
        }
    }

    /**
     * Resolve a list of tool names into a flat list of BaseTool instances.
     * Unknown tool names are silently ignored (logged to stderr).
     */
    public List<BaseTool> resolve(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return Collections.emptyList();
        }

        List<BaseTool> resolved = new ArrayList<>();
        for (String name : toolNames) {
            List<BaseTool> tools = toolCache.get(name.trim().toLowerCase());
            if (tools != null) {
                resolved.addAll(tools);
            } else {
                System.err.println("[ToolRegistry] Unknown tool name: '" + name + "'. Skipping.");
            }
        }
        return resolved;
    }

    /**
     * Get all registered tool names.
     */
    public Set<String> getAvailableToolNames() {
        return Collections.unmodifiableSet(toolCache.keySet());
    }

    /**
     * Get tools by a single name.
     */
    public List<BaseTool> get(String toolName) {
        return toolCache.getOrDefault(toolName, Collections.emptyList());
    }
}
