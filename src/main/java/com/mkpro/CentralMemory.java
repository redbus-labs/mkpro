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
import java.util.stream.Collectors;

/**
 * CentralMemory is the source of truth for the application's persistent state.
 * It uses MapDB to store memories, goals, agent statistics, agent configurations,
 * and server settings.
 * Refactored to open and close the database connection per operation.
 */
public class CentralMemory {

    public interface MemoryListener {
        void onUpdate(String key, Object value);
    }

    private final Path dbPath;
    private final List<MemoryListener> listeners = new ArrayList<>();
    private static CentralMemory instance;
    private static boolean centralMemoryLockWarned = false;
    private static DB fallbackDb;

    /**
     * Singleton accessor for CentralMemory.
     */
    public static synchronized CentralMemory getInstance() {
        if (instance == null) {
            instance = new CentralMemory();
        }
        return instance;
    }

    public CentralMemory() {
        this.dbPath = PathUtils.getBaseDocumentsPath().resolve("central_memory.db");
        try {
            PathUtils.ensureDirectoriesExist(dbPath);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Opens the MapDB database.
     * Note: MapDB's DB implements Closeable.
     */
    private DB openDB() {
        try {
            return DBMaker.fileDB(dbPath.toString())
                    .closeOnJvmShutdown()
                    .transactionEnable()
                    .make();
        } catch (Exception e) {
            if (!centralMemoryLockWarned) {
                System.err.println("\u001b[33m[Warning] central_memory.db is locked by another running instance of mkpro. Central memory operations will use in-memory fallbacks.\u001b[0m");
                centralMemoryLockWarned = true;
            }
            synchronized (CentralMemory.class) {
                if (fallbackDb == null || fallbackDb.isClosed()) {
                    fallbackDb = DBMaker.memoryDB()
                            .closeOnJvmShutdown()
                            .transactionEnable()
                            .make();
                }
                return fallbackDb;
            }
        }
    }

    public void addListener(MemoryListener l) {
        listeners.add(l);
    }

    private void notifyListeners(String key, Object value) {
        for (MemoryListener l : listeners) {
            l.onUpdate(key, value);
        }
    }

    // --- Memories ---

    public String getMemory(String path) {
        synchronized (this) {
            try (DB db = openDB()) {
                Map<String, String> memories = db.hashMap("memories", Serializer.STRING, Serializer.STRING).createOrOpen();
                return memories.getOrDefault(path, "");
            }
        }
    }

    public void saveMemory(String path, String content) {
        synchronized (this) {
            try (DB db = openDB()) {
                Map<String, String> memories = db.hashMap("memories", Serializer.STRING, Serializer.STRING).createOrOpen();
                memories.put(path, content);
                db.commit();
            }
        }
        notifyListeners("memory:" + path, content);
    }

    public Map<String, String> getAllMemories() {
        synchronized (this) {
            try (DB db = openDB()) {
                Map<String, String> memories = db.hashMap("memories", Serializer.STRING, Serializer.STRING).createOrOpen();
                return new HashMap<>(memories);
            }
        }
    }

    // --- Goals ---

    public List<Goal> getGoals(String path) {
        synchronized (this) {
            try (DB db = openDB()) {
                Map<String, List<Goal>> project_goals = db.hashMap("project_goals", Serializer.STRING, Serializer.JAVA).createOrOpen();
                List<Goal> goals = project_goals.get(path);
                return goals != null ? new ArrayList<>(goals) : Collections.emptyList();
            }
        }
    }

    public void addGoal(String path, Goal goal) {
        List<Goal> goals = new ArrayList<>(getGoals(path));
        goals.add(goal);
        setGoals(path, goals);
    }

    public void updateGoal(String path, Goal updatedGoal) {
        List<Goal> goals = new ArrayList<>(getGoals(path));
        boolean found = false;
        for (int i = 0; i < goals.size(); i++) {
            if (goals.get(i).getId().equals(updatedGoal.getId())) {
                goals.set(i, updatedGoal);
                found = true;
                break;
            }
        }
        if (found) {
            setGoals(path, goals);
        }
    }

    public void setGoals(String path, List<Goal> goals) {
        synchronized (this) {
            try (DB db = openDB()) {
                Map<String, List<Goal>> project_goals = db.hashMap("project_goals", Serializer.STRING, Serializer.JAVA).createOrOpen();
                project_goals.put(path, goals);
                db.commit();
            }
        }
        notifyListeners("goals:" + path, goals);
    }

    // --- Agent Stats ---

    public void saveAgentStat(AgentStat stat) {
        List<AgentStat> currentStats;
        synchronized (this) {
            try (DB db = openDB()) {
                @SuppressWarnings("unchecked")
                List<AgentStat> agent_stats = (List<AgentStat>) db.indexTreeList("agent_stats", Serializer.JAVA).createOrOpen();
                agent_stats.add(stat);
                db.commit();
                currentStats = new ArrayList<>(agent_stats);
            }
        }
        notifyListeners("agent_stats", currentStats);
    }

    public List<AgentStat> getAgentStats() {
        synchronized (this) {
            try (DB db = openDB()) {
                @SuppressWarnings("unchecked")
                List<AgentStat> agent_stats = (List<AgentStat>) db.indexTreeList("agent_stats", Serializer.JAVA).createOrOpen();
                return new ArrayList<>(agent_stats);
            }
        }
    }

    // --- Agent Configs ---

    public AgentConfig getAgentConfigs(String agentName) {
        synchronized (this) {
            try (DB db = openDB()) {
                Map<String, AgentConfig> agent_configs = db.hashMap("agent_configs", Serializer.STRING, Serializer.JAVA).createOrOpen();
                return agent_configs.get(agentName);
            }
        }
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
        synchronized (this) {
            try (DB db = openDB()) {
                Map<String, AgentConfig> agent_configs = db.hashMap("agent_configs", Serializer.STRING, Serializer.JAVA).createOrOpen();
                agent_configs.put(agentName, config);
                db.commit();
            }
        }
        notifyListeners("agent_config:" + agentName, config);
    }

    public void deleteAgentConfig(String agentName) {
        synchronized (this) {
            try (DB db = openDB()) {
                Map<String, AgentConfig> agent_configs = db.hashMap("agent_configs", Serializer.STRING, Serializer.JAVA).createOrOpen();
                agent_configs.remove(agentName);
                db.commit();
            }
        }
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
        synchronized (this) {
            try (DB db = openDB()) {
                Map<String, AgentConfig> agent_configs = db.hashMap("agent_configs", Serializer.STRING, Serializer.JAVA).createOrOpen();
                return new HashMap<>(agent_configs);
            }
        }
    }

    // --- Ollama Servers ---

    public List<String> getOllamaServers() {
        synchronized (this) {
            try (DB db = openDB()) {
                Map<String, List<String>> ollama_servers = db.hashMap("ollama_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
                List<String> list = ollama_servers.get("list");
                return list != null ? new ArrayList<>(list) : Collections.emptyList();
            }
        }
    }

    public void saveOllamaServers(List<String> servers) {
        synchronized (this) {
            try (DB db = openDB()) {
                Map<String, List<String>> ollama_servers = db.hashMap("ollama_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
                ollama_servers.put("list", servers);
                db.commit();
            }
        }
        notifyListeners("ollama_servers", servers);
    }

    public String getSelectedOllamaServer() {
        synchronized (this) {
            try (DB db = openDB()) {
                Map<String, String> selected_ollama_server = db.hashMap("selected_ollama_server", Serializer.STRING, Serializer.STRING).createOrOpen();
                return selected_ollama_server.getOrDefault("url", "");
            }
        }
    }

    public void saveSelectedOllamaServer(String url) {
        synchronized (this) {
            try (DB db = openDB()) {
                Map<String, String> selected_ollama_server = db.hashMap("selected_ollama_server", Serializer.STRING, Serializer.STRING).createOrOpen();
                selected_ollama_server.put("url", url);
                db.commit();
            }
        }
        notifyListeners("selected_ollama_server", url);
    }

    // --- MCP Servers ---

    public List<McpServer> getMcpServers() {
        synchronized (this) {
            try (DB db = openDB()) {
                Map<String, List<McpServer>> mcp_servers = db.hashMap("mcp_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
                List<McpServer> servers = mcp_servers.get("all");
                return servers != null ? new ArrayList<>(servers) : Collections.emptyList();
            }
        }
    }

    public void saveMcpServers(List<McpServer> servers) {
        synchronized (this) {
            try (DB db = openDB()) {
                Map<String, List<McpServer>> mcp_servers = db.hashMap("mcp_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
                mcp_servers.put("all", servers);
                db.commit();
            }
        }
        notifyListeners("mcp_servers", servers);
    }

    public void addMcpServer(McpServer server) {
        List<McpServer> servers = new ArrayList<>(getMcpServers());
        servers.removeIf(s -> s.getId().equals(server.getId()));
        servers.add(server);
        saveMcpServers(servers);
    }

    public void removeMcpServer(String id) {
        List<McpServer> servers = new ArrayList<>(getMcpServers());
        if (servers.removeIf(s -> s.getId().equals(id))) {
            saveMcpServers(servers);
        }
    }

    public void toggleMcpServer(String id) {
        List<McpServer> servers = new ArrayList<>(getMcpServers());
        for (McpServer s : servers) {
            if (s.getId().equals(id)) {
                s.setEnabled(!s.isEnabled());
                saveMcpServers(servers);
                break;
            }
        }
    }

    public List<McpServer> getEnabledMcpServers() {
        return getMcpServers().stream()
                .filter(McpServer::isEnabled)
                .collect(Collectors.toList());
    }

    public void updateMcpServerConnection(String id) {
        List<McpServer> servers = new ArrayList<>(getMcpServers());
        for (McpServer s : servers) {
            if (s.getId().equals(id)) {
                s.setLastConnectedAt(System.currentTimeMillis());
                saveMcpServers(servers);
                break;
            }
        }
    }

    // --- Synchronization ---

    @SuppressWarnings("unchecked")
    public void updateFromRemote(String key, Object value) {
        synchronized (this) {
            try (DB db = openDB()) {
                if (key.startsWith("memory:")) {
                    Map<String, String> memories = db.hashMap("memories", Serializer.STRING, Serializer.STRING).createOrOpen();
                    memories.put(key.substring(7), (String) value);
                } else if (key.startsWith("goals:")) {
                    Map<String, List<Goal>> project_goals = db.hashMap("project_goals", Serializer.STRING, Serializer.JAVA).createOrOpen();
                    project_goals.put(key.substring(6), (List<Goal>) value);
                } else if (key.equals("mcp_servers")) {
                    Map<String, List<McpServer>> mcp_servers = db.hashMap("mcp_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
                    mcp_servers.put("all", (List<McpServer>) value);
                } else if (key.equals("agent_stats")) {
                    List<AgentStat> agent_stats = (List<AgentStat>) db.indexTreeList("agent_stats", Serializer.JAVA).createOrOpen();
                    agent_stats.clear();
                    agent_stats.addAll((List<AgentStat>) value);
                } else if (key.startsWith("agent_config:")) {
                    Map<String, AgentConfig> agent_configs = db.hashMap("agent_configs", Serializer.STRING, Serializer.JAVA).createOrOpen();
                    agent_configs.put(key.substring(13), (AgentConfig) value);
                } else if (key.equals("ollama_servers")) {
                    Map<String, List<String>> ollama_servers = db.hashMap("ollama_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
                    ollama_servers.put("list", (List<String>) value);
                } else if (key.equals("selected_ollama_server")) {
                    Map<String, String> selected_ollama_server = db.hashMap("selected_ollama_server", Serializer.STRING, Serializer.STRING).createOrOpen();
                    selected_ollama_server.put("url", (String) value);
                }
                db.commit();
            }
        }
    }
}
