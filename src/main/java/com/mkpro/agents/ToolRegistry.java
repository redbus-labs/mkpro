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
        // Primary tool registrations
        toolCache.put("file_read", List.of(FileSystemTools.create(), MkProTools.createListDirTool()));
        toolCache.put("file_write", List.of(MkProTools.createWriteFileTool(), MkProTools.createReplaceInFileTool()));
        toolCache.put("safe_write", List.of(MkProTools.createSafeWriteFileTool()));
        toolCache.put("replace_in_file", List.of(MkProTools.createReplaceInFileTool()));
        toolCache.put("clipboard", List.of(ClipboardTools.create()));
        toolCache.put("shell", List.of(ShellTools.create()));
        toolCache.put("image", List.of(ImageTools.create()));
        toolCache.put("vision", List.of(VisionTool.create()));
        toolCache.put("audio", List.of(AudioTranscriptionTool.create()));
        toolCache.put("codebase_search", List.of(CodebaseSearchTools.create(vectorStore, embeddingService)));
        toolCache.put("index_codebase", List.of(IndexCodebaseTool.create(vectorStore, embeddingService)));
        toolCache.put("multi_project_search", List.of(MultiProjectSearchTools.create(embeddingService, vectorStore)));
        toolCache.put("mcp_scan", List.of(McpServerConnectTools.createScanProjectTool(), McpServerConnectTools.createSaveComponentTool()));
        toolCache.put("graph_memory", List.of(GraphMemoryTools.memorizeFact(), GraphMemoryTools.recallMemory(), GraphMemoryTools.visualizeGraph()));
        toolCache.put("fetch_url", List.of(FetchUrlTools.create()));
        toolCache.put("stats", List.of(StatsTools.createGetSessionStatsTool()));
        toolCache.put("central_memory", List.of(CentralMemoryTools.commitToMemory(), CentralMemoryTools.recallProjectMemory()));
        toolCache.put("scripting", List.of(ScriptTools.executeScript(), ScriptTools.createScript(), ScriptTools.listScripts()));
        toolCache.put("request_knowledge", List.of(RequestKnowledgeTool.create()));
        toolCache.put("verify_fact", List.of(VerifyFactTool.create()));
        toolCache.put("symbol_index", SymbolIndexTool.createSuite());
        toolCache.put("find_symbol", List.of(SymbolIndexTool.createFindSymbolTool()));
        toolCache.put("find_usages", List.of(SymbolIndexTool.createFindUsagesTool()));

        List<BaseTool> sshSuite = SshTools.createSuite();
        toolCache.put("ssh", sshSuite);
        toolCache.put("ssh_tools", sshSuite);
        toolCache.put("ssh_connect", List.of(SshTools.createConnectTool()));
        toolCache.put("ssh_exec", List.of(SshTools.createExecTool()));
        toolCache.put("ssh_file_transfer", List.of(SshTools.createFileTransferTool()));
        toolCache.put("ssh_disconnect", List.of(SshTools.createDisconnectTool()));
        toolCache.put("ssh_status", List.of(SshTools.createStatusTool()));

        List<BaseTool> seleniumTools = new ArrayList<>();
        try {
            seleniumTools.add(SeleniumTools.createNavigateTool());
            seleniumTools.add(SeleniumTools.createClickTool());
            seleniumTools.add(SeleniumTools.createTypeTool());
            seleniumTools.add(SeleniumTools.createScreenshotTool());
            seleniumTools.add(SeleniumTools.createGetHtmlTool());
            seleniumTools.add(SeleniumTools.createCloseTool());
        } catch (Exception ignored) {
        }
        toolCache.put("selenium", seleniumTools);

        // Modular ADK / polyglot aliases
        toolCache.put("read_file", toolCache.get("file_read"));
        toolCache.put("list_dir", toolCache.get("file_read"));
        toolCache.put("find_by_name", toolCache.get("file_read"));
        toolCache.put("grep_search", toolCache.get("file_read"));

        toolCache.put("write_file", toolCache.get("file_write"));

        toolCache.put("safe_write_file", toolCache.get("safe_write"));
        toolCache.put("edit_file", List.of(MkProTools.createReplaceInFileTool()));

        toolCache.put("run_command", toolCache.get("shell"));
        toolCache.put("execute_script", toolCache.get("scripting"));

        toolCache.put("get_status", List.of(ShellTools.create(), FileSystemTools.create(), MkProTools.createSafeWriteFileTool(), MkProTools.createReplaceInFileTool()));
        toolCache.put("submit_patch", List.of(ShellTools.create(), FileSystemTools.create(), MkProTools.createSafeWriteFileTool(), MkProTools.createReplaceInFileTool()));

        toolCache.put("search_similar_code", toolCache.get("codebase_search"));

        toolCache.put("get_code_neighbors", toolCache.get("graph_memory"));
        toolCache.put("get_code_subgraph", toolCache.get("graph_memory"));
        toolCache.put("memorize_fact", toolCache.get("graph_memory"));
        toolCache.put("recall_memory", toolCache.get("graph_memory"));
        toolCache.put("visualize_graph", toolCache.get("graph_memory"));

        toolCache.put("commit_to_memory", toolCache.get("central_memory"));
        toolCache.put("recall_project_memory", toolCache.get("central_memory"));
        toolCache.put("memory", toolCache.get("central_memory"));

        toolCache.put("analyze_image", toolCache.get("vision"));
        toolCache.put("image_read", toolCache.get("vision"));

        toolCache.put("transcribe_audio", toolCache.get("audio"));
        toolCache.put("audio_transcribe", toolCache.get("audio"));

        toolCache.put("save_goal", List.of(FileSystemTools.create(), MkProTools.createWriteFileTool(), MkProTools.createSafeWriteFileTool()));
        toolCache.put("read_goals", List.of(FileSystemTools.create(), MkProTools.createWriteFileTool(), MkProTools.createSafeWriteFileTool()));
        toolCache.put("goal_tracker", List.of(FileSystemTools.create(), MkProTools.createWriteFileTool(), MkProTools.createSafeWriteFileTool()));
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
