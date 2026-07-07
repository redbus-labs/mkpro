package com.mkpro;

import com.mkpro.models.AgentConfig;
import com.mkpro.models.AgentStat;
import com.mkpro.models.Goal;
import com.mkpro.models.McpServer;
import com.mkpro.models.Provider;
import com.mkpro.utils.PathUtils;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * CentralMemory is the source of truth for the application's persistent state.
 *
 * Architecture (Option B - Hot/Shared split):
 *
 * HOT STORE (per-instance, always open, zero contention):
 *   - Agent stats (high-frequency writes on every delegation)
 *   - Located in the project's .mkpro/ directory, named per-instance
 *
 * SHARED STORE (brief file lock, retry on contention):
 *   - Agent configs, goals, memories, MCP servers, Ollama servers
 *   - Located in ~/Documents/mkpro/central_memory.db
 *   - Opened briefly per write operation; reads served from cache
 *
 * LOCAL CACHE (in-memory, populated on startup, invalidated on writes):
 *   - Agent configs (read on every runner creation)
 *   - MCP servers (read during tool creation)
 *   - Avoids opening the shared DB for frequent reads
 */
public class CentralMemory {

    public interface MemoryListener {
        void onUpdate(String key, Object value);
    }

    private final Path sharedDbPath;
    private final DB localDb;
    private final List<MemoryListener> listeners = new ArrayList<>();
    private static CentralMemory instance;

    // --- Local Cache ---
    private final ConcurrentHashMap<String, AgentConfig> configCache = new ConcurrentHashMap<>();
    private volatile List<McpServer> mcpServerCache = null;
    private volatile List<String> ollamaServerCache = null;
    private volatile String selectedOllamaServerCache = null;
    private volatile boolean configCacheLoaded = false;

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 150;

    /**
     * Singleton accessor for CentralMemory.
     */
    public static synchronized CentralMemory getInstance() {
        if (instance == null) {
            instance = new CentralMemory();
        }
        return instance;
    }

    /**
     * Creates CentralMemory with default paths.
     * Hot store: .mkpro/local_stats.db (in project directory)
     * Shared store: ~/Documents/mkpro/central_memory.db
     */
    public CentralMemory() {
        this(PathUtils.getBaseDocumentsPath().resolve("central_memory.db"),
             resolveLocalDbPath());
    }

    /**
     * Creates CentralMemory with explicit paths (useful for testing).
     */
    public CentralMemory(Path sharedDbPath, Path localDbPath) {
        this.sharedDbPath = sharedDbPath;
        try {
            PathUtils.ensureDirectoriesExist(sharedDbPath);
            PathUtils.ensureDirectoriesExist(localDbPath);
        } catch (IOException e) {
            System.err.println("[CentralMemory] Warning: could not create directories: " + e.getMessage());
        }

        // Local DB is always open — no contention, instance-private
        this.localDb = DBMaker.fileDB(localDbPath.toString())
                .fileMmapEnableIfSupported()
                .transactionEnable()
                .make();

        // Populate cache from shared DB on startup
        loadCacheFromShared();

        // Register singleton
        instance = this;
    }

    private static Path resolveLocalDbPath() {
        Path mkproDir = PathUtils.getProjectPath().resolve(".mkpro");
        String dbName = System.getProperty("mkpro.db.name", "mkpro_data");
        return mkproDir.resolve(dbName + "_local_stats.db");
    }

    /**
     * Closes the local DB. Must be called on shutdown.
     */
    public void close() {
        try {
            if (localDb != null && !localDb.isClosed()) {
                localDb.close();
            }
        } catch (Exception e) {
            System.err.println("[CentralMemory] Error closing local DB: " + e.getMessage());
        }
    }

    // ==========================================================================
    // SHARED DB ACCESS (brief open/close with retry)
    // ==========================================================================

    /**
     * Opens the shared MapDB with retry logic for concurrent multi-instance access.
     */
    private DB openSharedDB() {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return DBMaker.fileDB(sharedDbPath.toString())
                        .transactionEnable()
                        .make();
            } catch (Exception e) {
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    System.err.println("\u001b[33m[CentralMemory] shared DB locked after " + MAX_RETRIES +
                            " retries. Using in-memory fallback.\u001b[0m");
                }
            }
        }
        return DBMaker.memoryDB().transactionEnable().make();
    }

    @FunctionalInterface
    private interface SharedDbAction<T> {
        T execute(DB db);
    }

    /**
     * Execute an action against the shared DB with automatic open/close.
     */
    private <T> T withSharedDb(SharedDbAction<T> action) {
        try (DB db = openSharedDB()) {
            return action.execute(db);
        }
    }

    /**
     * Execute a void action against the shared DB.
     */
    private void withSharedDbVoid(java.util.function.Consumer<DB> action) {
        try (DB db = openSharedDB()) {
            action.accept(db);
        }
    }

    // ==========================================================================
    // CACHE MANAGEMENT
    // ==========================================================================

    private void loadCacheFromShared() {
        try {
            withSharedDbVoid(db -> {
                // Load agent configs
                Map<String, AgentConfig> configs = db.hashMap("agent_configs", Serializer.STRING, Serializer.JAVA).createOrOpen();
                configCache.putAll(configs);
                configCacheLoaded = true;

                // Load MCP servers
                Map<String, List<McpServer>> mcpMap = db.hashMap("mcp_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
                List<McpServer> servers = mcpMap.get("all");
                mcpServerCache = servers != null ? new ArrayList<>(servers) : new ArrayList<>();

                // Load Ollama servers
                Map<String, List<String>> ollamaMap = db.hashMap("ollama_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
                List<String> ollamaList = ollamaMap.get("list");
                ollamaServerCache = ollamaList != null ? new ArrayList<>(ollamaList) : new ArrayList<>();

                // Load selected Ollama server
                Map<String, String> selectedMap = db.hashMap("selected_ollama_server", Serializer.STRING, Serializer.STRING).createOrOpen();
                selectedOllamaServerCache = selectedMap.getOrDefault("url", "");
            });
        } catch (Exception e) {
            System.err.println("[CentralMemory] Warning: could not load cache from shared DB: " + e.getMessage());
            configCacheLoaded = true; // Mark as loaded to avoid blocking on repeated failures
        }
    }

    /**
     * Force refresh the local cache from the shared DB.
     * Useful when receiving sync notifications from other instances.
     */
    public void refreshCache() {
        configCache.clear();
        mcpServerCache = null;
        ollamaServerCache = null;
        selectedOllamaServerCache = null;
        loadCacheFromShared();
    }

    public void addListener(MemoryListener l) {
        listeners.add(l);
    }

    private void notifyListeners(String key, Object value) {
        for (MemoryListener l : listeners) {
            try {
                l.onUpdate(key, value);
            } catch (Exception e) {
                // Don't let a listener failure break the operation
            }
        }
    }

    // ==========================================================================
    // HOT PATH — Agent Stats (local DB, always open, no contention)
    // ==========================================================================

    public void saveAgentStat(AgentStat stat) {
        synchronized (localDb) {
            @SuppressWarnings("unchecked")
            List<AgentStat> agent_stats = (List<AgentStat>) localDb.indexTreeList("agent_stats", Serializer.JAVA).createOrOpen();
            agent_stats.add(stat);
            localDb.commit();
        }
        notifyListeners("agent_stats", stat);
    }

    public List<AgentStat> getAgentStats() {
        synchronized (localDb) {
            @SuppressWarnings("unchecked")
            List<AgentStat> agent_stats = (List<AgentStat>) localDb.indexTreeList("agent_stats", Serializer.JAVA).createOrOpen();
            return new ArrayList<>(agent_stats);
        }
    }

    // ==========================================================================
    // CACHED READS — Agent Configs (served from cache, written to shared)
    // ==========================================================================

    public AgentConfig getAgentConfigs(String agentName) {
        return configCache.get(agentName);
    }

    public List<AgentConfig> getAgentConfigs(String agentName, String projectPath) {
        AgentConfig config = getAgentConfigs(agentName);
        if (config != null) {
            return Collections.singletonList(config);
        }
        return Collections.emptyList();
    }

    public Map<String, String> getAgentConfigsAsMap(String agentName, String projectPath) {
        List<AgentConfig> configs = getAgentConfigs(agentName, projectPath);
        Map<String, String> map = new HashMap<>();
        if (!configs.isEmpty()) {
            AgentConfig config = configs.get(0);
            map.put("modelName", config.getModelName());
            map.put("provider", config.getProvider() != null ? config.getProvider().name() : "");
        }
        return map;
    }

    public void saveAgentConfig(String agentName, AgentConfig config) {
        // Write to shared DB
        withSharedDbVoid(db -> {
            Map<String, AgentConfig> agent_configs = db.hashMap("agent_configs", Serializer.STRING, Serializer.JAVA).createOrOpen();
            agent_configs.put(agentName, config);
            db.commit();
        });
        // Update local cache
        configCache.put(agentName, config);
        notifyListeners("agent_config:" + agentName, config);
    }

    public void deleteAgentConfig(String agentName) {
        withSharedDbVoid(db -> {
            Map<String, AgentConfig> agent_configs = db.hashMap("agent_configs", Serializer.STRING, Serializer.JAVA).createOrOpen();
            agent_configs.remove(agentName);
            db.commit();
        });
        configCache.remove(agentName);
        notifyListeners("agent_config_deleted:" + agentName, null);
    }

    public void saveAgentConfig(String agentName, String modelName, String provider, String systemPrompt, String projectPath) {
        Provider p;
        try {
            p = Provider.valueOf(provider.toUpperCase());
        } catch (Exception e) {
            p = Provider.OLLAMA;
        }
        AgentConfig config = new AgentConfig(p, modelName);
        saveAgentConfig(agentName, config);
    }

    public Map<String, AgentConfig> getAllAgentConfigs() {
        return new HashMap<>(configCache);
    }

    // ==========================================================================
    // SHARED PATH — Memories (infrequent, always go to shared DB)
    // ==========================================================================

    public String getMemory(String path) {
        return withSharedDb(db -> {
            Map<String, String> memories = db.hashMap("memories", Serializer.STRING, Serializer.STRING).createOrOpen();
            return memories.getOrDefault(path, "");
        });
    }

    public void saveMemory(String path, String content) {
        withSharedDbVoid(db -> {
            Map<String, String> memories = db.hashMap("memories", Serializer.STRING, Serializer.STRING).createOrOpen();
            memories.put(path, content);
            db.commit();
        });
        notifyListeners("memory:" + path, content);
    }

    public Map<String, String> getAllMemories() {
        return withSharedDb(db -> {
            Map<String, String> memories = db.hashMap("memories", Serializer.STRING, Serializer.STRING).createOrOpen();
            return new HashMap<>(memories);
        });
    }

    // ==========================================================================
    // SHARED PATH — Goals (moderate frequency, shared DB)
    // ==========================================================================

    public List<Goal> getGoals(String path) {
        return withSharedDb(db -> {
            Map<String, List<Goal>> project_goals = db.hashMap("project_goals", Serializer.STRING, Serializer.JAVA).createOrOpen();
            List<Goal> goals = project_goals.get(path);
            return goals != null ? new ArrayList<>(goals) : Collections.emptyList();
        });
    }

    public void addGoal(String path, Goal goal) {
        withSharedDbVoid(db -> {
            Map<String, List<Goal>> project_goals = db.hashMap("project_goals", Serializer.STRING, Serializer.JAVA).createOrOpen();
            List<Goal> goals = project_goals.get(path);
            List<Goal> updated = goals != null ? new ArrayList<>(goals) : new ArrayList<>();
            updated.add(goal);
            project_goals.put(path, updated);
            db.commit();
        });
        notifyListeners("goals:" + path, null);
    }

    public void updateGoal(String path, Goal updatedGoal) {
        withSharedDbVoid(db -> {
            Map<String, List<Goal>> project_goals = db.hashMap("project_goals", Serializer.STRING, Serializer.JAVA).createOrOpen();
            List<Goal> goals = project_goals.get(path);
            if (goals == null) return;
            List<Goal> updated = new ArrayList<>(goals);
            boolean found = false;
            for (int i = 0; i < updated.size(); i++) {
                if (updated.get(i).getId().equals(updatedGoal.getId())) {
                    updated.set(i, updatedGoal);
                    found = true;
                    break;
                }
            }
            if (found) {
                project_goals.put(path, updated);
                db.commit();
            }
        });
        notifyListeners("goals:" + path, null);
    }

    public void setGoals(String path, List<Goal> goals) {
        withSharedDbVoid(db -> {
            Map<String, List<Goal>> project_goals = db.hashMap("project_goals", Serializer.STRING, Serializer.JAVA).createOrOpen();
            project_goals.put(path, goals);
            db.commit();
        });
        notifyListeners("goals:" + path, goals);
    }

    // ==========================================================================
    // CACHED PATH — MCP Servers (infrequent writes, cached reads)
    // ==========================================================================

    public List<McpServer> getMcpServers() {
        if (mcpServerCache != null) {
            return new ArrayList<>(mcpServerCache);
        }
        List<McpServer> result = withSharedDb(db -> {
            Map<String, List<McpServer>> mcp_servers = db.hashMap("mcp_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
            List<McpServer> servers = mcp_servers.get("all");
            return servers != null ? new ArrayList<>(servers) : new ArrayList<>();
        });
        mcpServerCache = result;
        return new ArrayList<>(result);
    }

    public void saveMcpServers(List<McpServer> servers) {
        withSharedDbVoid(db -> {
            Map<String, List<McpServer>> mcp_servers = db.hashMap("mcp_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
            mcp_servers.put("all", servers);
            db.commit();
        });
        mcpServerCache = new ArrayList<>(servers);
        notifyListeners("mcp_servers", servers);
    }

    public void addMcpServer(McpServer server) {
        withSharedDbVoid(db -> {
            Map<String, List<McpServer>> mcp_servers = db.hashMap("mcp_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
            List<McpServer> list = mcp_servers.get("all");
            List<McpServer> updated = list != null ? new ArrayList<>(list) : new ArrayList<>();
            updated.removeIf(s -> s.getId().equals(server.getId()));
            updated.add(server);
            mcp_servers.put("all", updated);
            db.commit();
            mcpServerCache = new ArrayList<>(updated);
        });
        notifyListeners("mcp_servers", null);
    }

    public void removeMcpServer(String id) {
        withSharedDbVoid(db -> {
            Map<String, List<McpServer>> mcp_servers = db.hashMap("mcp_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
            List<McpServer> list = mcp_servers.get("all");
            if (list == null) return;
            List<McpServer> updated = new ArrayList<>(list);
            if (updated.removeIf(s -> s.getId().equals(id))) {
                mcp_servers.put("all", updated);
                db.commit();
                mcpServerCache = new ArrayList<>(updated);
            }
        });
        notifyListeners("mcp_servers", null);
    }

    public void toggleMcpServer(String id) {
        withSharedDbVoid(db -> {
            Map<String, List<McpServer>> mcp_servers = db.hashMap("mcp_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
            List<McpServer> list = mcp_servers.get("all");
            if (list == null) return;
            List<McpServer> updated = new ArrayList<>(list);
            for (McpServer s : updated) {
                if (s.getId().equals(id)) {
                    s.setEnabled(!s.isEnabled());
                    mcp_servers.put("all", updated);
                    db.commit();
                    mcpServerCache = new ArrayList<>(updated);
                    break;
                }
            }
        });
        notifyListeners("mcp_servers", null);
    }

    public List<McpServer> getEnabledMcpServers() {
        return getMcpServers().stream()
                .filter(McpServer::isEnabled)
                .collect(Collectors.toList());
    }

    public void updateMcpServerConnection(String id) {
        withSharedDbVoid(db -> {
            Map<String, List<McpServer>> mcp_servers = db.hashMap("mcp_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
            List<McpServer> list = mcp_servers.get("all");
            if (list == null) return;
            List<McpServer> updated = new ArrayList<>(list);
            for (McpServer s : updated) {
                if (s.getId().equals(id)) {
                    s.setLastConnectedAt(System.currentTimeMillis());
                    mcp_servers.put("all", updated);
                    db.commit();
                    mcpServerCache = new ArrayList<>(updated);
                    break;
                }
            }
        });
    }

    // ==========================================================================
    // CACHED PATH — Ollama Servers (rare writes, cached reads)
    // ==========================================================================

    public List<String> getOllamaServers() {
        if (ollamaServerCache != null) {
            return new ArrayList<>(ollamaServerCache);
        }
        List<String> result = withSharedDb(db -> {
            Map<String, List<String>> ollama_servers = db.hashMap("ollama_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
            List<String> list = ollama_servers.get("list");
            return list != null ? new ArrayList<>(list) : new ArrayList<>();
        });
        ollamaServerCache = result;
        return new ArrayList<>(result);
    }

    public void saveOllamaServers(List<String> servers) {
        withSharedDbVoid(db -> {
            Map<String, List<String>> ollama_servers = db.hashMap("ollama_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
            ollama_servers.put("list", servers);
            db.commit();
        });
        ollamaServerCache = new ArrayList<>(servers);
        notifyListeners("ollama_servers", servers);
    }

    public String getSelectedOllamaServer() {
        if (selectedOllamaServerCache != null) {
            return selectedOllamaServerCache;
        }
        String result = withSharedDb(db -> {
            Map<String, String> selected = db.hashMap("selected_ollama_server", Serializer.STRING, Serializer.STRING).createOrOpen();
            return selected.getOrDefault("url", "");
        });
        selectedOllamaServerCache = result;
        return result;
    }

    public void saveSelectedOllamaServer(String url) {
        withSharedDbVoid(db -> {
            Map<String, String> selected = db.hashMap("selected_ollama_server", Serializer.STRING, Serializer.STRING).createOrOpen();
            selected.put("url", url);
            db.commit();
        });
        selectedOllamaServerCache = url;
        notifyListeners("selected_ollama_server", url);
    }

    // ==========================================================================
    // SYNCHRONIZATION — Called by SyncEngine when remote peer pushes updates
    // ==========================================================================

    @SuppressWarnings("unchecked")
    public void updateFromRemote(String key, Object value) {
        withSharedDbVoid(db -> {
            if (key.startsWith("memory:")) {
                Map<String, String> memories = db.hashMap("memories", Serializer.STRING, Serializer.STRING).createOrOpen();
                memories.put(key.substring(7), (String) value);
            } else if (key.startsWith("goals:")) {
                Map<String, List<Goal>> project_goals = db.hashMap("project_goals", Serializer.STRING, Serializer.JAVA).createOrOpen();
                project_goals.put(key.substring(6), (List<Goal>) value);
            } else if (key.equals("mcp_servers")) {
                Map<String, List<McpServer>> mcp_servers = db.hashMap("mcp_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
                List<McpServer> servers = (List<McpServer>) value;
                mcp_servers.put("all", servers);
                mcpServerCache = servers != null ? new ArrayList<>(servers) : new ArrayList<>();
            } else if (key.equals("agent_stats")) {
                // Remote stats go to shared DB (not local) for cross-instance visibility
                List<AgentStat> stats = (List<AgentStat>) value;
                List<AgentStat> agent_stats = (List<AgentStat>) db.indexTreeList("agent_stats_shared", Serializer.JAVA).createOrOpen();
                agent_stats.clear();
                agent_stats.addAll(stats);
            } else if (key.startsWith("agent_config:")) {
                Map<String, AgentConfig> agent_configs = db.hashMap("agent_configs", Serializer.STRING, Serializer.JAVA).createOrOpen();
                String agentName = key.substring(13);
                agent_configs.put(agentName, (AgentConfig) value);
                // Update local cache
                configCache.put(agentName, (AgentConfig) value);
            } else if (key.equals("ollama_servers")) {
                Map<String, List<String>> ollama_servers = db.hashMap("ollama_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
                List<String> servers = (List<String>) value;
                ollama_servers.put("list", servers);
                ollamaServerCache = servers != null ? new ArrayList<>(servers) : new ArrayList<>();
            } else if (key.equals("selected_ollama_server")) {
                Map<String, String> selected = db.hashMap("selected_ollama_server", Serializer.STRING, Serializer.STRING).createOrOpen();
                selected.put("url", (String) value);
                selectedOllamaServerCache = (String) value;
            }
            db.commit();
        });
    }
}
