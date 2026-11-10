package com.mkpro.agents;

import com.google.adk.memory.EmbeddingService;
import com.google.adk.memory.MapDBVectorStore;
import com.google.adk.tools.BaseTool;
import com.mkpro.facts.VerifyFactTool;
import com.mkpro.knowledge.RequestKnowledgeTool;
import com.mkpro.scripting.ScriptTools;
import com.mkpro.tools.*;

import java.util.*;

/**
 * ToolRegistry provides a declarative mapping from tool names (strings in YAML)
 * to actual BaseTool instances. Tools are created lazily and cached.
 *
 * Supported tool names:
 *   file_read, file_write, safe_write, clipboard, shell, image,
 *   codebase_search, multi_project_search, mcp_scan, graph_memory,
 *   fetch_url, stats, selenium, ssh, ssh_connect, ssh_exec,
 *   ssh_file_transfer, ssh_disconnect, ssh_status, replace_in_file
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
        toolCache.put("read_file", List.of(FileSystemTools.create()));
        toolCache.put("list_dir", List.of(MkProTools.createListDirTool()));
        toolCache.put("file_write", List.of(MkProTools.createWriteFileTool(), MkProTools.createReplaceInFileTool()));
        toolCache.put("write_file", List.of(MkProTools.createWriteFileTool()));
        toolCache.put("edit_file", List.of(MkProTools.createReplaceInFileTool()));
        toolCache.put("submit_patch", List.of(MkProTools.createReplaceInFileTool(), MkProTools.createSafeWriteFileTool()));
        toolCache.put("safe_write", List.of(MkProTools.createSafeWriteFileTool()));
        toolCache.put("safe_write_file", List.of(MkProTools.createSafeWriteFileTool()));
        toolCache.put("replace_in_file", List.of(MkProTools.createReplaceInFileTool()));

        // Clipboard
        toolCache.put("clipboard", List.of(ClipboardTools.create()));

        // Shell execution
        toolCache.put("shell", List.of(ShellTools.create()));
        toolCache.put("run_command", List.of(ShellTools.create()));
        toolCache.put("run_shell", List.of(ShellTools.create()));
        toolCache.put("git_tools", List.of(ShellTools.create()));

        // Background job management
        List<BaseTool> bgJobTools = List.of(
            BackgroundJobTools.createListBackgroundJobsTool(),
            BackgroundJobTools.createKillBackgroundJobTool()
        );
        toolCache.put("background_jobs", bgJobTools);
        toolCache.put("list_background_jobs", List.of(BackgroundJobTools.createListBackgroundJobsTool()));
        toolCache.put("kill_background_job", List.of(BackgroundJobTools.createKillBackgroundJobTool()));

        // Image analysis
        toolCache.put("image", List.of(ImageTools.create(), MkProTools.createImageCropTool()));
        toolCache.put("image_crop", List.of(MkProTools.createImageCropTool()));

        // Vision (multimodal LLM image analysis)
        toolCache.put("vision", List.of(VisionTool.create()));
        toolCache.put("analyze_image", List.of(VisionTool.create()));

        // Audio transcription (multimodal LLM audio understanding)
        toolCache.put("audio", List.of(AudioTranscriptionTool.create()));
        toolCache.put("transcribe_audio", List.of(AudioTranscriptionTool.create()));

        // Codebase search (vector embeddings)
        toolCache.put("codebase_search", List.of(CodebaseSearchTools.create(vectorStore, embeddingService)));
        toolCache.put("search_codebase", List.of(CodebaseSearchTools.create(vectorStore, embeddingService)));
        toolCache.put("search_similar_code", List.of(CodebaseSearchTools.create(vectorStore, embeddingService)));

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
        toolCache.put("get_status", List.of(StatsTools.createGetSessionStatsTool()));

        // Central memory (commit/recall project-scoped memory)
        toolCache.put("central_memory", List.of(
            CentralMemoryTools.commitToMemory(),
            CentralMemoryTools.recallProjectMemory()
        ));

        // Groovy script engine (core Java only, algorithm/data processing)
        toolCache.put("scripting", List.of(
            ScriptTools.executeScript(),
            ScriptTools.createScript(),
            ScriptTools.listScripts()
        ));

        // Knowledge request (agents signal knowledge gaps to scheduler)
        toolCache.put("request_knowledge", List.of(
            RequestKnowledgeTool.create()
        ));

        // Fact verification (math formulas + relationship checking)
        toolCache.put("verify_fact", List.of(
            VerifyFactTool.create()
        ));

        // Symbol index tools
        toolCache.put("symbol_index", SymbolIndexTool.createSuite());
        toolCache.put("find_symbol", List.of(SymbolIndexTool.createFindSymbolTool()));
        toolCache.put("find_usages", List.of(SymbolIndexTool.createFindUsagesTool()));
        toolCache.put("get_code_neighbors", SymbolIndexTool.createSuite());
        toolCache.put("get_code_subgraph", SymbolIndexTool.createSuite());

        // Peer agent tools (default instances so early YAML resolution never fails; updated when P2P starts)
        toolCache.put("list_peers", List.of(AskPeerAgentTool.createListPeersTool()));
        toolCache.put("ask_peer_agent", List.of(AskPeerAgentTool.create(null, "local")));

        // Persistent SSH tool suite & individual tools
        toolCache.put("ssh", SshTools.createSuite());
        toolCache.put("ssh_tools", SshTools.createSuite());
        toolCache.put("ssh_connect", List.of(SshTools.createConnectTool()));
        toolCache.put("ssh_exec", List.of(SshTools.createExecTool()));
        toolCache.put("ssh_file_transfer", List.of(SshTools.createFileTransferTool()));
        toolCache.put("ssh_disconnect", List.of(SshTools.createDisconnectTool()));
        toolCache.put("ssh_status", List.of(SshTools.createStatusTool()));

        // VM-First & Local Fallback Computer Use tool suite
        List<BaseTool> computerSuite = ComputerUseTools.createSuite();
        toolCache.put("computer", computerSuite);
        toolCache.put("computer_use", computerSuite);
        toolCache.put("computer_init_vm", List.of(ComputerUseTools.createInitVmTool()));
        toolCache.put("computer_screenshot", List.of(ComputerUseTools.createScreenshotTool()));
        toolCache.put("computer_click", List.of(ComputerUseTools.createClickTool()));
        toolCache.put("computer_move_or_drag", List.of(ComputerUseTools.createMoveOrDragTool()));
        toolCache.put("computer_type", List.of(ComputerUseTools.createTypeTool()));
        toolCache.put("computer_key", List.of(ComputerUseTools.createKeyTool()));
        toolCache.put("computer_scroll", List.of(ComputerUseTools.createScrollTool()));
        toolCache.put("computer_launch_app", List.of(ComputerUseTools.createLaunchAppTool()));

        // Set-of-Mark (SoM) Browser Use tool suite
        List<BaseTool> browserSuite = BrowserUseTools.createSuite();
        toolCache.put("browser", browserSuite);
        toolCache.put("browser_use", browserSuite);
        toolCache.put("browser_open", List.of(BrowserUseTools.createOpenTool()));
        toolCache.put("browser_snapshot", List.of(BrowserUseTools.createSnapshotTool()));
        toolCache.put("browser_act", List.of(BrowserUseTools.createActTool()));
        toolCache.put("browser_extract_markdown", List.of(BrowserUseTools.createExtractMarkdownTool()));
        toolCache.put("browser_close", List.of(BrowserUseTools.createCloseTool()));

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

        Map<String, BaseTool> deduplicated = new LinkedHashMap<>();
        for (String name : toolNames) {
            List<BaseTool> tools = toolCache.get(name.trim().toLowerCase());
            if (tools != null) {
                for (BaseTool tool : tools) {
                    if (tool != null && tool.name() != null) {
                        deduplicated.putIfAbsent(tool.name(), tool);
                    }
                }
            } else {
                System.err.println("[ToolRegistry] Unknown tool name: '" + name + "'. Skipping.");
            }
        }
        return new ArrayList<>(deduplicated.values());
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
