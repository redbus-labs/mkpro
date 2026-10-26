package com.mkpro;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mkpro.core.StoreKeys;
import com.mkpro.infra.ssh.SshSandboxConfig;
import com.mkpro.models.AgentConfig;
import com.mkpro.models.AgentStat;
import com.mkpro.models.Goal;
import com.mkpro.models.McpServer;
import com.mkpro.models.Provider;
import com.mkpro.models.notes.NoteItem;
import com.mkpro.models.notes.TaskItem;
import com.mkpro.plugins.FilterConfig;
import com.mkpro.utils.PathUtils;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *   - Agent configs, goals, memories, MCP servers, Ollama servers, SSH Sandbox config, generic objects
 *   - Located in ~/Documents/mkpro/central_memory.db
 *   - Opened briefly per write operation; reads served from cache
 *
 * LOCAL CACHE (in-memory, populated on startup, invalidated on writes):
 *   - Agent configs (read on every runner creation)
 *   - MCP servers (read during tool creation)
 *   - SSH Sandbox config (read during connect / auto-connect)
 *   - Avoids opening the shared DB for frequent reads
 */
public class CentralMemory {

    public interface MemoryListener {
        void onUpdate(String key, Object value);
    }

    private static final Logger logger = LoggerFactory.getLogger(CentralMemory.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Path sharedDbPath;
    private final DB localDb;
    private final List<MemoryListener> listeners = new ArrayList<>();
    private static CentralMemory instance;

    // --- Local Cache ---
    private final ConcurrentHashMap<String, AgentConfig> configCache = new ConcurrentHashMap<>();
    private volatile List<McpServer> mcpServerCache = null;
    private volatile List<String> ollamaServerCache = null;
    private volatile String selectedOllamaServerCache = null;
    private volatile List<String> openAiServerCache = null;
    private volatile String selectedOpenAiServerCache = null;
    private volatile SshSandboxConfig sshSandboxConfigCache = null;
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

                // Load OpenAI servers
                Map<String, List<String>> openAiMap = db.hashMap("openai_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
                List<String> openAiList = openAiMap.get("list");
                openAiServerCache = openAiList != null ? new ArrayList<>(openAiList) : new ArrayList<>();

                // Load selected OpenAI server
                Map<String, String> selectedOpenAiMap = db.hashMap("selected_openai_server", Serializer.STRING, Serializer.STRING).createOrOpen();
                selectedOpenAiServerCache = selectedOpenAiMap.getOrDefault("url", "");

                // Load SSH sandbox config
                Map<String, SshSandboxConfig> sshMap = db.hashMap("ssh_sandbox_config", Serializer.STRING, Serializer.JAVA).createOrOpen();
                sshSandboxConfigCache = sshMap.get("default");
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
        openAiServerCache = null;
        selectedOpenAiServerCache = null;
        sshSandboxConfigCache = null;
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

    public static String getCurrentProjectPath() {
        return PathUtils.getProjectPath().toAbsolutePath().normalize().toString();
    }

    public static String getProjectKeyPrefix(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return getCurrentProjectPath() + "::";
        }
        return Path.of(projectPath).toAbsolutePath().normalize().toString() + "::";
    }

    public static String getCurrentProjectKeyPrefix() {
        return getCurrentProjectPath() + "::";
    }

    public boolean hasProjectAgentConfig(String agentName) {
        String prefix = getCurrentProjectKeyPrefix();
        return configCache.containsKey(prefix + agentName) || configCache.containsKey(prefix + "default");
    }

    public boolean hasExplicitProjectAgentConfig(String agentName) {
        return configCache.containsKey(getCurrentProjectKeyPrefix() + agentName);
    }

    public Map<String, AgentConfig> getGlobalAgentConfigs() {
        Map<String, AgentConfig> globals = new HashMap<>();
        configCache.forEach((key, value) -> {
            if (!key.contains("::")) {
                globals.put(key, value);
            }
        });
        return globals;
    }

    public Map<String, AgentConfig> getProjectAgentConfigs() {
        String prefix = getCurrentProjectKeyPrefix();
        Map<String, AgentConfig> projectConfigs = new HashMap<>();
        configCache.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                projectConfigs.put(key.substring(prefix.length()), value);
            }
        });
        return projectConfigs;
    }

    public AgentConfig getAgentConfig(String agentName) {
        String prefix = getCurrentProjectKeyPrefix();
        AgentConfig config = configCache.get(prefix + agentName);
        if (config != null) {
            return config;
        }
        config = configCache.get(prefix + "default");
        if (config != null) {
            return config;
        }
        config = configCache.get(agentName);
        if (config != null) {
            return config;
        }
        return configCache.get("default");
    }

    public AgentConfig getAgentConfigs(String agentName) {
        return getAgentConfig(agentName);
    }

    public List<AgentConfig> getAgentConfigs(String agentName, String projectPath) {
        if (projectPath != null && !projectPath.isBlank()) {
            String normalizedPrefix = getProjectKeyPrefix(projectPath);
            AgentConfig scoped = configCache.get(normalizedPrefix + agentName);
            if (scoped == null) {
                scoped = configCache.get(normalizedPrefix + "default");
            }
            if (scoped != null) {
                return Collections.singletonList(scoped);
            }
        }
        AgentConfig config = getAgentConfig(agentName);
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
        String scopedKey = agentName.contains("::") ? agentName : getCurrentProjectKeyPrefix() + agentName;
        // Write to shared DB
        withSharedDbVoid(db -> {
            Map<String, AgentConfig> agent_configs = db.hashMap("agent_configs", Serializer.STRING, Serializer.JAVA).createOrOpen();
            agent_configs.put(scopedKey, config);
            db.commit();
        });
        // Update local cache
        configCache.put(scopedKey, config);
        notifyListeners("agent_config:" + scopedKey, config);
    }

    public void saveGlobalAgentConfig(String agentName, AgentConfig config) {
        withSharedDbVoid(db -> {
            Map<String, AgentConfig> agent_configs = db.hashMap("agent_configs", Serializer.STRING, Serializer.JAVA).createOrOpen();
            agent_configs.put(agentName, config);
            db.commit();
        });
        configCache.put(agentName, config);
        notifyListeners("agent_config:" + agentName, config);
    }

    public void deleteAgentConfig(String agentName) {
        String scopedKey = agentName.contains("::") ? agentName : getCurrentProjectKeyPrefix() + agentName;
        withSharedDbVoid(db -> {
            Map<String, AgentConfig> agent_configs = db.hashMap("agent_configs", Serializer.STRING, Serializer.JAVA).createOrOpen();
            agent_configs.remove(scopedKey);
            db.commit();
        });
        configCache.remove(scopedKey);
        notifyListeners("agent_config_deleted:" + scopedKey, null);
    }

    public void deleteGlobalAgentConfig(String agentName) {
        withSharedDbVoid(db -> {
            Map<String, AgentConfig> agent_configs = db.hashMap("agent_configs", Serializer.STRING, Serializer.JAVA).createOrOpen();
            agent_configs.remove(agentName);
            db.commit();
        });
        configCache.remove(agentName);
        notifyListeners("agent_config_deleted:" + agentName, null);
    }

    public void clearProjectAgentConfigs() {
        String prefix = getCurrentProjectKeyPrefix();
        List<String> keysToRemove = configCache.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .collect(Collectors.toList());
        withSharedDbVoid(db -> {
            Map<String, AgentConfig> agent_configs = db.hashMap("agent_configs", Serializer.STRING, Serializer.JAVA).createOrOpen();
            for (String key : keysToRemove) {
                agent_configs.remove(key);
            }
            db.commit();
        });
        for (String key : keysToRemove) {
            configCache.remove(key);
            notifyListeners("agent_config_deleted:" + key, null);
        }
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
        Map<String, AgentConfig> effective = new HashMap<>(getGlobalAgentConfigs());
        Map<String, AgentConfig> projectConfigs = getProjectAgentConfigs();
        effective.putAll(projectConfigs);
        return effective;
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

    /**
     * Dump all shared stores as a map of storeName → map of key→value (as strings).
     * Used by the web MapDB browser for visualization/debugging.
     */
    public Map<String, Map<String, String>> dumpAllStores() {
        Map<String, Map<String, String>> result = new java.util.LinkedHashMap<>();
        
        // Shared stores
        Map<String, Map<String, String>> shared = withSharedDb(db -> {
            Map<String, Map<String, String>> r = new java.util.LinkedHashMap<>();

            // Memories
            Map<String, String> memories = db.hashMap("memories", Serializer.STRING, Serializer.STRING).createOrOpen();
            r.put("memories", new java.util.LinkedHashMap<>(memories));

            // Agent configs
            Map<String, Object> configs = db.hashMap("agent_configs", Serializer.STRING, Serializer.JAVA).createOrOpen();
            Map<String, String> configStrings = new java.util.LinkedHashMap<>();
            configs.forEach((k, v) -> configStrings.put(k, v != null ? v.toString() : "null"));
            r.put("agent_configs", configStrings);

            // MCP servers
            Map<String, Object> mcpMap = db.hashMap("mcp_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
            Map<String, String> mcpStrings = new java.util.LinkedHashMap<>();
            mcpMap.forEach((k, v) -> mcpStrings.put(k, v != null ? v.toString() : "null"));
            r.put("mcp_servers", mcpStrings);

            // Ollama servers
            Map<String, Object> ollamaMap = db.hashMap("ollama_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
            Map<String, String> ollamaStrings = new java.util.LinkedHashMap<>();
            ollamaMap.forEach((k, v) -> ollamaStrings.put(k, v != null ? v.toString() : "null"));
            r.put("ollama_servers", ollamaStrings);

            // OpenAI servers
            Map<String, Object> openAiMap = db.hashMap("openai_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
            Map<String, String> openAiStrings = new java.util.LinkedHashMap<>();
            openAiMap.forEach((k, v) -> openAiStrings.put(k, v != null ? v.toString() : "null"));
            r.put("openai_servers", openAiStrings);

            // Goals
            Map<String, Object> goalsMap = db.hashMap("project_goals", Serializer.STRING, Serializer.JAVA).createOrOpen();
            Map<String, String> goalStrings = new java.util.LinkedHashMap<>();
            goalsMap.forEach((k, v) -> goalStrings.put(k, v != null ? v.toString() : "null"));
            r.put("project_goals", goalStrings);

            // SSH Sandbox Config (dump safe representation)
            Map<String, Object> sshMap = db.hashMap("ssh_sandbox_config", Serializer.STRING, Serializer.JAVA).createOrOpen();
            Map<String, String> sshStrings = new java.util.LinkedHashMap<>();
            sshMap.forEach((k, v) -> {
                if (v instanceof SshSandboxConfig) {
                    sshStrings.put(k, ((SshSandboxConfig) v).toSafeMap().toString());
                } else {
                    sshStrings.put(k, v != null ? v.toString() : "null");
                }
            });
            r.put("ssh_sandbox_config", sshStrings);

            // Filter Config
            Map<String, Object> filterMap = db.hashMap("filter_config", Serializer.STRING, Serializer.JAVA).createOrOpen();
            Map<String, String> filterStrings = new java.util.LinkedHashMap<>();
            filterMap.forEach((k, v) -> filterStrings.put(k, v != null ? v.toString() : "null"));
            r.put("filter_config", filterStrings);

            // Generic store
            Map<String, Object> genericMap = db.hashMap("generic_store", Serializer.STRING, Serializer.JAVA).createOrOpen();
            Map<String, String> genericStrings = new java.util.LinkedHashMap<>();
            genericMap.forEach((k, v) -> genericStrings.put(k, v != null ? v.toString() : "null"));
            r.put("generic_store", genericStrings);

            // Tasks
            Map<String, String> tasksMap = db.hashMap("mkpro_tasks", Serializer.STRING, Serializer.STRING).createOrOpen();
            r.put("mkpro_tasks", new java.util.LinkedHashMap<>(tasksMap));

            // Notes
            Map<String, String> notesMap = db.hashMap("mkpro_notes", Serializer.STRING, Serializer.STRING).createOrOpen();
            r.put("mkpro_notes", new java.util.LinkedHashMap<>(notesMap));

            return r;
        });
        result.putAll(shared);

        // Local stats (agent_stats from local DB)
        try {
            @SuppressWarnings("unchecked")
            List<AgentStat> stats = (List<AgentStat>) localDb.indexTreeList("agent_stats", Serializer.JAVA).createOrOpen();
            Map<String, String> statsMap = new java.util.LinkedHashMap<>();
            int count = Math.min(stats.size(), 100); // Last 100 entries
            for (int i = Math.max(0, stats.size() - count); i < stats.size(); i++) {
                AgentStat stat = stats.get(i);
                statsMap.put(String.valueOf(i), stat != null ? stat.toString() : "null");
            }
            result.put("agent_stats (local, last 100)", statsMap);
        } catch (Exception e) {
            result.put("agent_stats (local)", Map.of("error", e.getMessage()));
        }

        // Session logs (from ActionLogger)
        try {
            List<String> logs = ActionLogger.getLogs();
            Map<String, String> logMap = new java.util.LinkedHashMap<>();
            int start = Math.max(0, logs.size() - 50); // Last 50 log entries
            for (int i = start; i < logs.size(); i++) {
                logMap.put(String.valueOf(i), logs.get(i));
            }
            result.put("session_logs (last 50)", logMap);
        } catch (Exception e) {
            result.put("session_logs", Map.of("error", e.getMessage()));
        }

        return result;
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
    // SHARED PATH — Tasks & Notes (shared DB, JSON serialized)
    // ==========================================================================

    private TaskItem deserializeTaskWithKeyMetadata(String key, String json) {
        if (json == null) {
            return null;
        }
        try {
            TaskItem task = objectMapper.readValue(json, TaskItem.class);
            if (task != null) {
                if (key != null && key.contains("::")) {
                    int idx = key.indexOf("::");
                    String prefixPath = key.substring(0, idx);
                    String rawId = key.substring(idx + 2);
                    if (task.getId() == null || task.getId().isBlank() || task.getId().contains("::")) {
                        task.setId(rawId);
                    }
                    if (task.getProjectPath() == null || task.getProjectPath().isBlank() || "global".equalsIgnoreCase(task.getProjectPath())) {
                        task.setProjectPath(prefixPath);
                    }
                    task.setProjectScoped(true);
                } else {
                    if (task.getId() == null || task.getId().isBlank()) {
                        task.setId(key);
                    }
                    if (task.getProjectPath() == null || task.getProjectPath().isBlank()) {
                        task.setProjectPath("global");
                    }
                    task.setProjectScoped(!"global".equalsIgnoreCase(task.getProjectPath()));
                }
            }
            return task;
        } catch (Exception e) {
            logger.error("Failed to deserialize task for key {}: {}", key, json, e);
            return null;
        }
    }

    public List<TaskItem> getProjectTasks() {
        return getProjectTasks(getCurrentProjectPath());
    }

    public List<TaskItem> getProjectTasks(String projectPath) {
        String prefix = getProjectKeyPrefix(projectPath);
        String normalizedPath = prefix.substring(0, prefix.length() - 2);
        try {
            List<TaskItem> tasks = withSharedDb(db -> {
                Map<String, String> map = db.hashMap("mkpro_tasks", Serializer.STRING, Serializer.STRING).createOrOpen();
                List<TaskItem> list = new ArrayList<>();
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    if (entry.getKey().startsWith(prefix)) {
                        TaskItem task = deserializeTaskWithKeyMetadata(entry.getKey(), entry.getValue());
                        if (task != null) {
                            task.setProjectPath(normalizedPath);
                            task.setProjectScoped(true);
                            list.add(task);
                        }
                    }
                }
                return list;
            });
            return tasks != null ? tasks : Collections.emptyList();
        } catch (Exception e) {
            logger.error("Failed to retrieve project tasks for {}", projectPath, e);
            return Collections.emptyList();
        }
    }

    public List<TaskItem> getGlobalTasks() {
        try {
            List<TaskItem> tasks = withSharedDb(db -> {
                Map<String, String> map = db.hashMap("mkpro_tasks", Serializer.STRING, Serializer.STRING).createOrOpen();
                List<TaskItem> list = new ArrayList<>();
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    if (!entry.getKey().contains("::")) {
                        TaskItem task = deserializeTaskWithKeyMetadata(entry.getKey(), entry.getValue());
                        if (task != null) {
                            task.setProjectPath("global");
                            task.setProjectScoped(false);
                            list.add(task);
                        }
                    }
                }
                return list;
            });
            return tasks != null ? tasks : Collections.emptyList();
        } catch (Exception e) {
            logger.error("Failed to retrieve global tasks", e);
            return Collections.emptyList();
        }
    }

    public Map<String, List<TaskItem>> getTasksGroupedByProject() {
        String currentProject = getCurrentProjectPath();
        try {
            Map<String, List<TaskItem>> grouped = withSharedDb(db -> {
                Map<String, String> map = db.hashMap("mkpro_tasks", Serializer.STRING, Serializer.STRING).createOrOpen();
                Map<String, List<TaskItem>> temp = new LinkedHashMap<>();
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    TaskItem task = deserializeTaskWithKeyMetadata(entry.getKey(), entry.getValue());
                    if (task != null) {
                        String groupKey = (task.getProjectPath() == null || task.getProjectPath().isBlank() || "global".equalsIgnoreCase(task.getProjectPath()))
                                ? "global"
                                : task.getProjectPath();
                        temp.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(task);
                    }
                }
                // Order map so currentProject comes first, then other projects, then "global"
                Map<String, List<TaskItem>> ordered = new LinkedHashMap<>();
                if (temp.containsKey(currentProject)) {
                    ordered.put(currentProject, temp.get(currentProject));
                }
                for (Map.Entry<String, List<TaskItem>> e : temp.entrySet()) {
                    if (!e.getKey().equals(currentProject) && !"global".equalsIgnoreCase(e.getKey())) {
                        ordered.put(e.getKey(), e.getValue());
                    }
                }
                if (temp.containsKey("global")) {
                    ordered.put("global", temp.get("global"));
                }
                return ordered;
            });
            return grouped != null ? grouped : Collections.emptyMap();
        } catch (Exception e) {
            logger.error("Failed to retrieve tasks grouped by project", e);
            return Collections.emptyMap();
        }
    }

    public List<TaskItem> getAllTasks() {
        String currentProject = getCurrentProjectPath();
        String currentPrefix = currentProject + "::";
        try {
            List<TaskItem> tasks = withSharedDb(db -> {
                Map<String, String> map = db.hashMap("mkpro_tasks", Serializer.STRING, Serializer.STRING).createOrOpen();
                List<TaskItem> currentProjectList = new ArrayList<>();
                Set<String> currentProjectIds = new HashSet<>();
                List<TaskItem> otherProjectsList = new ArrayList<>();
                List<TaskItem> globalList = new ArrayList<>();

                // First pass: collect current project tasks and other project tasks
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    String key = entry.getKey();
                    if (key.startsWith(currentPrefix)) {
                        TaskItem task = deserializeTaskWithKeyMetadata(key, entry.getValue());
                        if (task != null) {
                            currentProjectList.add(task);
                            currentProjectIds.add(task.getId());
                        }
                    } else if (key.contains("::")) {
                        TaskItem task = deserializeTaskWithKeyMetadata(key, entry.getValue());
                        if (task != null) {
                            otherProjectsList.add(task);
                        }
                    }
                }

                // Second pass: collect global tasks (current project's task takes precedence if sharing exact same raw ID)
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    String key = entry.getKey();
                    if (!key.contains("::")) {
                        TaskItem task = deserializeTaskWithKeyMetadata(key, entry.getValue());
                        if (task != null && !currentProjectIds.contains(task.getId())) {
                            globalList.add(task);
                        }
                    }
                }

                List<TaskItem> combined = new ArrayList<>(currentProjectList.size() + otherProjectsList.size() + globalList.size());
                combined.addAll(currentProjectList);
                combined.addAll(otherProjectsList);
                combined.addAll(globalList);
                return combined;
            });
            return tasks != null ? tasks : Collections.emptyList();
        } catch (Exception e) {
            logger.error("Failed to retrieve tasks", e);
            return Collections.emptyList();
        }
    }

    public TaskItem getTask(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String rawId = id.contains("::") ? id.substring(id.indexOf("::") + 2) : id;
        try {
            return withSharedDb(db -> {
                Map<String, String> map = db.hashMap("mkpro_tasks", Serializer.STRING, Serializer.STRING).createOrOpen();
                // 1. Check current project prefix + rawId (or exact id if it already has ::)
                String primaryKey = id.contains("::") ? id : getCurrentProjectKeyPrefix() + rawId;
                String json = map.get(primaryKey);
                if (json != null) {
                    return deserializeTaskWithKeyMetadata(primaryKey, json);
                }
                // 2. Fall back to raw id (global key)
                json = map.get(rawId);
                if (json != null) {
                    return deserializeTaskWithKeyMetadata(rawId, json);
                }
                // 3. Check if any entry in map has key ending with "::" + rawId
                String suffix = "::" + rawId;
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    if (entry.getKey().endsWith(suffix)) {
                        return deserializeTaskWithKeyMetadata(entry.getKey(), entry.getValue());
                    }
                }
                return null;
            });
        } catch (Exception e) {
            logger.error("Failed to retrieve task for id: {}", id, e);
            return null;
        }
    }

    public TaskItem saveTask(TaskItem task) {
        if (task == null) {
            return null;
        }
        try {
            if (task.getId() == null || task.getId().isBlank()) {
                task.setId(UUID.randomUUID().toString());
            }
            String rawId = task.getId();
            if (rawId.contains("::")) {
                rawId = rawId.substring(rawId.indexOf("::") + 2);
                task.setId(rawId);
            }

            final String storeKey;
            if (task.isProjectScoped()) {
                String projPath = task.getProjectPath();
                if (projPath == null || projPath.isBlank() || "global".equalsIgnoreCase(projPath)) {
                    projPath = getCurrentProjectPath();
                } else {
                    projPath = Path.of(projPath).toAbsolutePath().normalize().toString();
                }
                task.setProjectPath(projPath);
                storeKey = getProjectKeyPrefix(projPath) + rawId;
            } else {
                task.setProjectPath("global");
                storeKey = rawId;
            }

            task.setUpdatedAt(System.currentTimeMillis());
            String json = objectMapper.writeValueAsString(task);
            withSharedDb(db -> {
                Map<String, String> map = db.hashMap("mkpro_tasks", Serializer.STRING, Serializer.STRING).createOrOpen();
                map.put(storeKey, json);
                db.commit();
                return null;
            });
            notifyListeners("task:" + task.getId(), task);
            return task;
        } catch (Exception e) {
            logger.error("Failed to save task: {}", task.getId(), e);
            return null;
        }
    }

    public TaskItem saveGlobalTask(TaskItem task) {
        if (task == null) {
            return null;
        }
        task.setProjectScoped(false);
        task.setProjectPath("global");
        return saveTask(task);
    }

    public boolean deleteTask(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        String rawId = id.contains("::") ? id.substring(id.indexOf("::") + 2) : id;
        try {
            Boolean removed = withSharedDb(db -> {
                Map<String, String> map = db.hashMap("mkpro_tasks", Serializer.STRING, Serializer.STRING).createOrOpen();
                boolean exists = false;
                if (map.remove(getCurrentProjectKeyPrefix() + rawId) != null) {
                    exists = true;
                }
                if (map.remove(rawId) != null) {
                    exists = true;
                }
                if (id.contains("::") && map.remove(id) != null) {
                    exists = true;
                }
                String suffix = "::" + rawId;
                List<String> otherKeys = map.keySet().stream()
                        .filter(k -> k.endsWith(suffix))
                        .collect(Collectors.toList());
                for (String k : otherKeys) {
                    if (map.remove(k) != null) {
                        exists = true;
                    }
                }
                if (exists) {
                    db.commit();
                }
                return exists;
            });
            boolean result = Boolean.TRUE.equals(removed);
            if (result) {
                notifyListeners("task_deleted:" + rawId, null);
            }
            return result;
        } catch (Exception e) {
            logger.error("Failed to delete task: {}", id, e);
            return false;
        }
    }

    public TaskItem toggleTask(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String rawId = id.contains("::") ? id.substring(id.indexOf("::") + 2) : id;
        try {
            TaskItem updatedTask = withSharedDb(db -> {
                Map<String, String> map = db.hashMap("mkpro_tasks", Serializer.STRING, Serializer.STRING).createOrOpen();
                String targetKey = null;
                String json = null;

                String primaryKey = id.contains("::") ? id : getCurrentProjectKeyPrefix() + rawId;
                json = map.get(primaryKey);
                if (json != null) {
                    targetKey = primaryKey;
                } else {
                    json = map.get(rawId);
                    if (json != null) {
                        targetKey = rawId;
                    } else {
                        String suffix = "::" + rawId;
                        for (Map.Entry<String, String> entry : map.entrySet()) {
                            if (entry.getKey().endsWith(suffix)) {
                                targetKey = entry.getKey();
                                json = entry.getValue();
                                break;
                            }
                        }
                    }
                }

                if (targetKey == null || json == null) {
                    return null;
                }
                try {
                    TaskItem task = deserializeTaskWithKeyMetadata(targetKey, json);
                    if (task == null) {
                        return null;
                    }
                    if ("DONE".equalsIgnoreCase(task.getStatus())) {
                        task.setStatus("TODO");
                    } else {
                        task.setStatus("DONE");
                    }
                    task.setUpdatedAt(System.currentTimeMillis());
                    map.put(targetKey, objectMapper.writeValueAsString(task));
                    db.commit();
                    return task;
                } catch (Exception e) {
                    logger.error("Failed to toggle task JSON for id: {}", id, e);
                    return null;
                }
            });
            if (updatedTask != null) {
                notifyListeners("task:" + updatedTask.getId(), updatedTask);
            }
            return updatedTask;
        } catch (Exception e) {
            logger.error("Failed to toggle task: {}", id, e);
            return null;
        }
    }

    public void clearProjectTasks() {
        String prefix = getCurrentProjectKeyPrefix();
        try {
            List<String> removedIds = withSharedDb(db -> {
                Map<String, String> map = db.hashMap("mkpro_tasks", Serializer.STRING, Serializer.STRING).createOrOpen();
                List<String> keysToRemove = map.keySet().stream()
                        .filter(k -> k.startsWith(prefix))
                        .collect(Collectors.toList());
                List<String> ids = new ArrayList<>();
                for (String key : keysToRemove) {
                    map.remove(key);
                    ids.add(key.substring(prefix.length()));
                }
                if (!keysToRemove.isEmpty()) {
                    db.commit();
                }
                return ids;
            });
            if (removedIds != null) {
                for (String rawId : removedIds) {
                    notifyListeners("task_deleted:" + rawId, null);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to clear project tasks", e);
        }
    }

    public void clearGlobalTasks() {
        try {
            List<String> removedIds = withSharedDb(db -> {
                Map<String, String> map = db.hashMap("mkpro_tasks", Serializer.STRING, Serializer.STRING).createOrOpen();
                List<String> keysToRemove = map.keySet().stream()
                        .filter(k -> !k.contains("::"))
                        .collect(Collectors.toList());
                for (String key : keysToRemove) {
                    map.remove(key);
                }
                if (!keysToRemove.isEmpty()) {
                    db.commit();
                }
                return keysToRemove;
            });
            if (removedIds != null) {
                for (String rawId : removedIds) {
                    notifyListeners("task_deleted:" + rawId, null);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to clear global tasks", e);
        }
    }

    private NoteItem deserializeNoteWithKeyMetadata(String key, String json) {
        if (json == null) {
            return null;
        }
        try {
            NoteItem note = objectMapper.readValue(json, NoteItem.class);
            if (note != null) {
                if (key != null && key.contains("::")) {
                    int idx = key.indexOf("::");
                    String prefixPath = key.substring(0, idx);
                    String rawId = key.substring(idx + 2);
                    if (note.getId() == null || note.getId().isBlank() || note.getId().contains("::")) {
                        note.setId(rawId);
                    }
                    if (note.getProjectPath() == null || note.getProjectPath().isBlank() || "global".equalsIgnoreCase(note.getProjectPath())) {
                        note.setProjectPath(prefixPath);
                    }
                    note.setProjectScoped(true);
                } else {
                    if (note.getId() == null || note.getId().isBlank()) {
                        note.setId(key);
                    }
                    if (note.getProjectPath() == null || note.getProjectPath().isBlank()) {
                        note.setProjectPath("global");
                    }
                    note.setProjectScoped(!"global".equalsIgnoreCase(note.getProjectPath()));
                }
            }
            return note;
        } catch (Exception e) {
            logger.error("Failed to deserialize note for key {}: {}", key, json, e);
            return null;
        }
    }

    public List<NoteItem> getProjectNotes() {
        return getProjectNotes(getCurrentProjectPath());
    }

    public List<NoteItem> getProjectNotes(String projectPath) {
        String prefix = getProjectKeyPrefix(projectPath);
        String normalizedPath = prefix.substring(0, prefix.length() - 2);
        try {
            List<NoteItem> notes = withSharedDb(db -> {
                Map<String, String> map = db.hashMap("mkpro_notes", Serializer.STRING, Serializer.STRING).createOrOpen();
                List<NoteItem> list = new ArrayList<>();
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    if (entry.getKey().startsWith(prefix)) {
                        NoteItem note = deserializeNoteWithKeyMetadata(entry.getKey(), entry.getValue());
                        if (note != null) {
                            note.setProjectPath(normalizedPath);
                            note.setProjectScoped(true);
                            list.add(note);
                        }
                    }
                }
                return list;
            });
            return notes != null ? notes : Collections.emptyList();
        } catch (Exception e) {
            logger.error("Failed to retrieve project notes for {}", projectPath, e);
            return Collections.emptyList();
        }
    }

    public List<NoteItem> getGlobalNotes() {
        try {
            List<NoteItem> notes = withSharedDb(db -> {
                Map<String, String> map = db.hashMap("mkpro_notes", Serializer.STRING, Serializer.STRING).createOrOpen();
                List<NoteItem> list = new ArrayList<>();
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    if (!entry.getKey().contains("::")) {
                        NoteItem note = deserializeNoteWithKeyMetadata(entry.getKey(), entry.getValue());
                        if (note != null) {
                            note.setProjectPath("global");
                            note.setProjectScoped(false);
                            list.add(note);
                        }
                    }
                }
                return list;
            });
            return notes != null ? notes : Collections.emptyList();
        } catch (Exception e) {
            logger.error("Failed to retrieve global notes", e);
            return Collections.emptyList();
        }
    }

    public Map<String, List<NoteItem>> getNotesGroupedByProject() {
        String currentProject = getCurrentProjectPath();
        try {
            Map<String, List<NoteItem>> grouped = withSharedDb(db -> {
                Map<String, String> map = db.hashMap("mkpro_notes", Serializer.STRING, Serializer.STRING).createOrOpen();
                Map<String, List<NoteItem>> temp = new LinkedHashMap<>();
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    NoteItem note = deserializeNoteWithKeyMetadata(entry.getKey(), entry.getValue());
                    if (note != null) {
                        String groupKey = (note.getProjectPath() == null || note.getProjectPath().isBlank() || "global".equalsIgnoreCase(note.getProjectPath()))
                                ? "global"
                                : note.getProjectPath();
                        temp.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(note);
                    }
                }
                // Order map so currentProject comes first, then other projects, then "global"
                Map<String, List<NoteItem>> ordered = new LinkedHashMap<>();
                if (temp.containsKey(currentProject)) {
                    ordered.put(currentProject, temp.get(currentProject));
                }
                for (Map.Entry<String, List<NoteItem>> e : temp.entrySet()) {
                    if (!e.getKey().equals(currentProject) && !"global".equalsIgnoreCase(e.getKey())) {
                        ordered.put(e.getKey(), e.getValue());
                    }
                }
                if (temp.containsKey("global")) {
                    ordered.put("global", temp.get("global"));
                }
                return ordered;
            });
            return grouped != null ? grouped : Collections.emptyMap();
        } catch (Exception e) {
            logger.error("Failed to retrieve notes grouped by project", e);
            return Collections.emptyMap();
        }
    }

    public List<NoteItem> getAllNotes() {
        String currentProject = getCurrentProjectPath();
        String currentPrefix = currentProject + "::";
        try {
            List<NoteItem> notes = withSharedDb(db -> {
                Map<String, String> map = db.hashMap("mkpro_notes", Serializer.STRING, Serializer.STRING).createOrOpen();
                List<NoteItem> currentProjectList = new ArrayList<>();
                Set<String> currentProjectIds = new HashSet<>();
                List<NoteItem> otherProjectsList = new ArrayList<>();
                List<NoteItem> globalList = new ArrayList<>();

                // First pass: collect current project notes and other project notes
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    String key = entry.getKey();
                    if (key.startsWith(currentPrefix)) {
                        NoteItem note = deserializeNoteWithKeyMetadata(key, entry.getValue());
                        if (note != null) {
                            currentProjectList.add(note);
                            currentProjectIds.add(note.getId());
                        }
                    } else if (key.contains("::")) {
                        NoteItem note = deserializeNoteWithKeyMetadata(key, entry.getValue());
                        if (note != null) {
                            otherProjectsList.add(note);
                        }
                    }
                }

                // Second pass: collect global notes (current project's note takes precedence if sharing exact same raw ID)
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    String key = entry.getKey();
                    if (!key.contains("::")) {
                        NoteItem note = deserializeNoteWithKeyMetadata(key, entry.getValue());
                        if (note != null && !currentProjectIds.contains(note.getId())) {
                            globalList.add(note);
                        }
                    }
                }

                List<NoteItem> combined = new ArrayList<>(currentProjectList.size() + otherProjectsList.size() + globalList.size());
                combined.addAll(currentProjectList);
                combined.addAll(otherProjectsList);
                combined.addAll(globalList);
                return combined;
            });
            return notes != null ? notes : Collections.emptyList();
        } catch (Exception e) {
            logger.error("Failed to retrieve notes", e);
            return Collections.emptyList();
        }
    }

    public NoteItem getNote(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String rawId = id.contains("::") ? id.substring(id.indexOf("::") + 2) : id;
        try {
            return withSharedDb(db -> {
                Map<String, String> map = db.hashMap("mkpro_notes", Serializer.STRING, Serializer.STRING).createOrOpen();
                // 1. Check current project prefix + rawId (or exact id if it already has ::)
                String primaryKey = id.contains("::") ? id : getCurrentProjectKeyPrefix() + rawId;
                String json = map.get(primaryKey);
                if (json != null) {
                    return deserializeNoteWithKeyMetadata(primaryKey, json);
                }
                // 2. Fall back to raw id (global key)
                json = map.get(rawId);
                if (json != null) {
                    return deserializeNoteWithKeyMetadata(rawId, json);
                }
                // 3. Check if any entry in map has key ending with "::" + rawId
                String suffix = "::" + rawId;
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    if (entry.getKey().endsWith(suffix)) {
                        return deserializeNoteWithKeyMetadata(entry.getKey(), entry.getValue());
                    }
                }
                return null;
            });
        } catch (Exception e) {
            logger.error("Failed to retrieve note for id: {}", id, e);
            return null;
        }
    }

    public NoteItem saveNote(NoteItem note) {
        if (note == null) {
            return null;
        }
        try {
            if (note.getId() == null || note.getId().isBlank()) {
                note.setId(UUID.randomUUID().toString());
            }
            String rawId = note.getId();
            if (rawId.contains("::")) {
                rawId = rawId.substring(rawId.indexOf("::") + 2);
                note.setId(rawId);
            }

            final String storeKey;
            if (note.isProjectScoped()) {
                String projPath = note.getProjectPath();
                if (projPath == null || projPath.isBlank() || "global".equalsIgnoreCase(projPath)) {
                    projPath = getCurrentProjectPath();
                } else {
                    projPath = Path.of(projPath).toAbsolutePath().normalize().toString();
                }
                note.setProjectPath(projPath);
                storeKey = getProjectKeyPrefix(projPath) + rawId;
            } else {
                note.setProjectPath("global");
                storeKey = rawId;
            }

            note.setUpdatedAt(System.currentTimeMillis());
            String json = objectMapper.writeValueAsString(note);
            withSharedDb(db -> {
                Map<String, String> map = db.hashMap("mkpro_notes", Serializer.STRING, Serializer.STRING).createOrOpen();
                map.put(storeKey, json);
                db.commit();
                return null;
            });
            notifyListeners("note:" + note.getId(), note);
            return note;
        } catch (Exception e) {
            logger.error("Failed to save note: {}", note.getId(), e);
            return null;
        }
    }

    public NoteItem saveGlobalNote(NoteItem note) {
        if (note == null) {
            return null;
        }
        note.setProjectScoped(false);
        note.setProjectPath("global");
        return saveNote(note);
    }

    public boolean deleteNote(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        String rawId = id.contains("::") ? id.substring(id.indexOf("::") + 2) : id;
        try {
            Boolean removed = withSharedDb(db -> {
                Map<String, String> map = db.hashMap("mkpro_notes", Serializer.STRING, Serializer.STRING).createOrOpen();
                boolean exists = false;
                if (map.remove(getCurrentProjectKeyPrefix() + rawId) != null) {
                    exists = true;
                }
                if (map.remove(rawId) != null) {
                    exists = true;
                }
                if (id.contains("::") && map.remove(id) != null) {
                    exists = true;
                }
                String suffix = "::" + rawId;
                List<String> otherKeys = map.keySet().stream()
                        .filter(k -> k.endsWith(suffix))
                        .collect(Collectors.toList());
                for (String k : otherKeys) {
                    if (map.remove(k) != null) {
                        exists = true;
                    }
                }
                if (exists) {
                    db.commit();
                }
                return exists;
            });
            boolean result = Boolean.TRUE.equals(removed);
            if (result) {
                notifyListeners("note_deleted:" + rawId, null);
            }
            return result;
        } catch (Exception e) {
            logger.error("Failed to delete note: {}", id, e);
            return false;
        }
    }

    public void clearProjectNotes() {
        String prefix = getCurrentProjectKeyPrefix();
        try {
            List<String> removedIds = withSharedDb(db -> {
                Map<String, String> map = db.hashMap("mkpro_notes", Serializer.STRING, Serializer.STRING).createOrOpen();
                List<String> keysToRemove = map.keySet().stream()
                        .filter(k -> k.startsWith(prefix))
                        .collect(Collectors.toList());
                List<String> ids = new ArrayList<>();
                for (String key : keysToRemove) {
                    map.remove(key);
                    ids.add(key.substring(prefix.length()));
                }
                if (!keysToRemove.isEmpty()) {
                    db.commit();
                }
                return ids;
            });
            if (removedIds != null) {
                for (String rawId : removedIds) {
                    notifyListeners("note_deleted:" + rawId, null);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to clear project notes", e);
        }
    }

    public void clearGlobalNotes() {
        try {
            List<String> removedIds = withSharedDb(db -> {
                Map<String, String> map = db.hashMap("mkpro_notes", Serializer.STRING, Serializer.STRING).createOrOpen();
                List<String> keysToRemove = map.keySet().stream()
                        .filter(k -> !k.contains("::"))
                        .collect(Collectors.toList());
                for (String key : keysToRemove) {
                    map.remove(key);
                }
                if (!keysToRemove.isEmpty()) {
                    db.commit();
                }
                return keysToRemove;
            });
            if (removedIds != null) {
                for (String rawId : removedIds) {
                    notifyListeners("note_deleted:" + rawId, null);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to clear global notes", e);
        }
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
    // CACHED PATH — OpenAI Servers (rare writes, cached reads)
    // ==========================================================================

    public List<String> getOpenAiServers() {
        if (openAiServerCache != null) {
            return new ArrayList<>(openAiServerCache);
        }
        List<String> result = withSharedDb(db -> {
            Map<String, List<String>> openai_servers = db.hashMap("openai_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
            List<String> list = openai_servers.get("list");
            return list != null ? new ArrayList<>(list) : new ArrayList<>();
        });
        openAiServerCache = result;
        return new ArrayList<>(result);
    }

    public void saveOpenAiServers(List<String> servers) {
        withSharedDbVoid(db -> {
            Map<String, List<String>> openai_servers = db.hashMap("openai_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
            openai_servers.put("list", servers);
            db.commit();
        });
        openAiServerCache = new ArrayList<>(servers);
        notifyListeners("openai_servers", servers);
    }

    public String getSelectedOpenAiServer() {
        if (selectedOpenAiServerCache != null) {
            return selectedOpenAiServerCache;
        }
        String result = withSharedDb(db -> {
            Map<String, String> selected = db.hashMap("selected_openai_server", Serializer.STRING, Serializer.STRING).createOrOpen();
            return selected.getOrDefault("url", "");
        });
        selectedOpenAiServerCache = result;
        return result;
    }

    public void saveSelectedOpenAiServer(String url) {
        withSharedDbVoid(db -> {
            Map<String, String> selected = db.hashMap("selected_openai_server", Serializer.STRING, Serializer.STRING).createOrOpen();
            selected.put("url", url);
            db.commit();
        });
        selectedOpenAiServerCache = url;
        notifyListeners("selected_openai_server", url);
    }

    // ==========================================================================
    // CACHED PATH — SSH Sandbox Config (MapDB shared DB, cached reads)
    // ==========================================================================

    /**
     * Retrieve the persistent SSH Sandbox configuration.
     * Served from local cache; loaded from shared MapDB if needed.
     */
    public SshSandboxConfig getSshSandboxConfig() {
        if (sshSandboxConfigCache != null) {
            return sshSandboxConfigCache;
        }
        SshSandboxConfig cfg = withSharedDb(db -> {
            Map<String, SshSandboxConfig> sshMap = db.hashMap("ssh_sandbox_config", Serializer.STRING, Serializer.JAVA).createOrOpen();
            return sshMap.get("default");
        });
        sshSandboxConfigCache = cfg;
        return cfg;
    }

    /**
     * Save/update the SSH Sandbox configuration in shared MapDB.
     */
    public void saveSshSandboxConfig(SshSandboxConfig config) {
        withSharedDbVoid(db -> {
            Map<String, SshSandboxConfig> sshMap = db.hashMap("ssh_sandbox_config", Serializer.STRING, Serializer.JAVA).createOrOpen();
            if (config != null) {
                config.setUpdatedAt(System.currentTimeMillis());
                sshMap.put("default", config);
            } else {
                sshMap.remove("default");
            }
            db.commit();
        });
        sshSandboxConfigCache = config;
        notifyListeners(StoreKeys.SSH_SANDBOX_CONFIG, config);
    }

    /**
     * Delete the persistent SSH Sandbox configuration.
     */
    public void deleteSshSandboxConfig() {
        saveSshSandboxConfig(null);
    }

    // ==========================================================================
    // CACHED PATH — Filter Config (MapDB shared DB)
    // ==========================================================================

    public void saveFilterConfig(FilterConfig config) {
        if (config == null) return;
        withSharedDbVoid(db -> {
            Map<String, FilterConfig> map = db.hashMap("filter_config", Serializer.STRING, Serializer.JAVA).createOrOpen();
            map.put("current", config);
            db.commit();
        });
        put(StoreKeys.FILTER_CONFIG, config);
        notifyListeners(StoreKeys.FILTER_CONFIG, config);
    }

    public FilterConfig getFilterConfig() {
        FilterConfig cfg = withSharedDb(db -> {
            Map<String, FilterConfig> map = db.hashMap("filter_config", Serializer.STRING, Serializer.JAVA).createOrOpen();
            return map.get("current");
        });
        if (cfg != null) {
            return cfg;
        }
        FilterConfig generic = get(StoreKeys.FILTER_CONFIG, FilterConfig.class);
        if (generic != null) {
            return generic;
        }
        return new FilterConfig();
    }

    // ==========================================================================
    // GENERIC STORE — Key-Value generic object persistence
    // ==========================================================================

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> clazz) {
        return withSharedDb(db -> {
            Map<String, Object> genericStore = db.hashMap("generic_store", Serializer.STRING, Serializer.JAVA).createOrOpen();
            Object val = genericStore.get(key);
            if (val != null && clazz.isInstance(val)) {
                return (T) val;
            }
            return null;
        });
    }

    public void put(String key, Object value) {
        withSharedDbVoid(db -> {
            Map<String, Object> genericStore = db.hashMap("generic_store", Serializer.STRING, Serializer.JAVA).createOrOpen();
            if (value != null) {
                genericStore.put(key, value);
            } else {
                genericStore.remove(key);
            }
            db.commit();
        });
        notifyListeners(key, value);
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
            } else if (key.equals("openai_servers")) {
                Map<String, List<String>> openai_servers = db.hashMap("openai_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
                List<String> servers = (List<String>) value;
                openai_servers.put("list", servers);
                openAiServerCache = servers != null ? new ArrayList<>(servers) : new ArrayList<>();
            } else if (key.equals("selected_openai_server")) {
                Map<String, String> selected = db.hashMap("selected_openai_server", Serializer.STRING, Serializer.STRING).createOrOpen();
                selected.put("url", (String) value);
                selectedOpenAiServerCache = (String) value;
            } else if (key.equals("ssh_sandbox_config") || key.equals(StoreKeys.SSH_SANDBOX_CONFIG)) {
                Map<String, SshSandboxConfig> sshMap = db.hashMap("ssh_sandbox_config", Serializer.STRING, Serializer.JAVA).createOrOpen();
                SshSandboxConfig cfg = (SshSandboxConfig) value;
                if (cfg != null) {
                    sshMap.put("default", cfg);
                } else {
                    sshMap.remove("default");
                }
                sshSandboxConfigCache = cfg;
            } else if (key.equals("filter_config") || key.equals(StoreKeys.FILTER_CONFIG)) {
                Map<String, FilterConfig> filterMap = db.hashMap("filter_config", Serializer.STRING, Serializer.JAVA).createOrOpen();
                FilterConfig cfg = (FilterConfig) value;
                if (cfg != null) {
                    filterMap.put("current", cfg);
                } else {
                    filterMap.remove("current");
                }
            } else if (key.startsWith("task:")) {
                Map<String, String> tasks = db.hashMap("mkpro_tasks", Serializer.STRING, Serializer.STRING).createOrOpen();
                tasks.put(key.substring(5), (String) value);
            } else if (key.startsWith("note:")) {
                Map<String, String> notes = db.hashMap("mkpro_notes", Serializer.STRING, Serializer.STRING).createOrOpen();
                notes.put(key.substring(5), (String) value);
            } else {
                Map<String, Object> genericStore = db.hashMap("generic_store", Serializer.STRING, Serializer.JAVA).createOrOpen();
                if (value != null) {
                    genericStore.put(key, value);
                } else {
                    genericStore.remove(key);
                }
            }
            db.commit();
        });
    }
}
