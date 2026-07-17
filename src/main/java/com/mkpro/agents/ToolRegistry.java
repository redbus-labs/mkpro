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
        toolCache.put("file_read", List.of(FileSystemTools.create(), MkProTools.createListDirTool()));
        toolCache.put("file_write", List.of(MkProTools.createWriteFileTool()));
        toolCache.put("safe_write", List.of(MkProTools.createSafeWriteFileTool()));

        // Clipboard
        toolCache.put("clipboard", List.of(ClipboardTools.create()));

        // Shell execution
        toolCache.put("shell", List.of(ShellTools.create()));

        // Image analysis
        toolCache.put("image", List.of(ImageTools.create()));

        // Vision (multimodal LLM image analysis)
        toolCache.put("vision", List.of(VisionTool.create()));

        // Audio transcription (multimodal LLM audio understanding)
        toolCache.put("audio", List.of(AudioTranscriptionTool.create()));

        // Codebase search (vector embeddings)
        toolCache.put("codebase_search", List.of(CodebaseSearchTools.create(vectorStore, embeddingService)));

        // Codebase indexing (populate vector store)
        toolCache.put("index_codebase", List.of(IndexCodebaseTool.create(vectorStore, embeddingService)));

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

        // Central memory (commit/recall project-scoped memory)
        toolCache.put("central_memory", List.of(
            CentralMemoryTools.commitToMemory(),
            CentralMemoryTools.recallProjectMemory()
        ));

        // Groovy script engine (core Java only, algorithm/data processing)
        toolCache.put("scripting", List.of(
            com.mkpro.scripting.ScriptTools.executeScript(),
            com.mkpro.scripting.ScriptTools.createScript(),
            com.mkpro.scripting.ScriptTools.listScripts()
        ));

        // Knowledge request (agents signal knowledge gaps to scheduler)
        toolCache.put("request_knowledge", List.of(
            com.mkpro.knowledge.RequestKnowledgeTool.create()
        ));

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
     * Late-register the ask_peer_agent tool (requires P2PMessageBus which isn't available at construction).
     */
    public void registerPeerAgentTool(com.mkpro.infra.network.messaging.P2PMessageBus messageBus, String instanceId) {
        toolCache.put("ask_peer_agent", List.of(AskPeerAgentTool.create(messageBus, instanceId)));
        toolCache.put("list_peers", List.of(AskPeerAgentTool.createListPeersTool()));
    }

    /**
     * Get tools by a single name.
     */
    public List<BaseTool> get(String toolName) {
        return toolCache.getOrDefault(toolName, Collections.emptyList());
    }
}
