package com.mkpro;

import com.mkpro.models.AgentConfig;
import com.mkpro.models.AgentStat;
import com.mkpro.models.Goal;
import com.mkpro.models.McpServer;
import com.mkpro.models.Provider;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;

import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * CentralMemory is the source of truth for the application's persistent state.
 * It uses MapDB to store memories, goals, agent statistics, agent configurations,
 * and server settings.
 */
public class CentralMemory {

    public interface MemoryListener {
        void onUpdate(String key, Object value);
    }

    private final DB db;
    private final ConcurrentMap<String, String> memories;
    private final ConcurrentMap<String, List<Goal>> project_goals;
    private final ConcurrentMap<String, List<McpServer>> mcp_servers;
    private final List<AgentStat> agent_stats;
    private final ConcurrentMap<String, AgentConfig> agent_configs;
    private final ConcurrentMap<String, List<String>> ollama_servers;
    private final ConcurrentMap<String, String> selected_ollama_server;

    private final List<MemoryListener> listeners = new ArrayList<>();
    private static CentralMemory instance;

    /**
     * Singleton accessor for CentralMemory.
     */
    public static synchronized CentralMemory getInstance() {
        if (instance == null) {
            instance = new CentralMemory();
        }
        return instance;
    }

    @SuppressWarnings("unchecked")
    public CentralMemory() {
        this.db = DBMaker.fileDB("central_memory.db")
                .closeOnJvmShutdown()
                .transactionEnable()
                .make();

        this.memories = db.hashMap("memories", Serializer.STRING, Serializer.STRING).createOrOpen();
        this.project_goals = db.hashMap("project_goals", Serializer.STRING, Serializer.JAVA).createOrOpen();
        this.mcp_servers = db.hashMap("mcp_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
        this.agent_stats = (List<AgentStat>) db.indexTreeList("agent_stats", Serializer.JAVA).createOrOpen();
        this.agent_configs = db.hashMap("agent_configs", Serializer.STRING, Serializer.JAVA).createOrOpen();
        this.ollama_servers = db.hashMap("ollama_servers", Serializer.STRING, Serializer.JAVA).createOrOpen();
        this.selected_ollama_server = db.hashMap("selected_ollama_server", Serializer.STRING, Serializer.STRING).createOrOpen();
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
        return memories.getOrDefault(path, "");
    }

    public void saveMemory(String path, String content) {
        memories.put(path, content);
        db.commit();
        notifyListeners("memory:" + path, content);
    }

    public Map<String, String> getAllMemories() {
        return new HashMap<>(memories);
    }

    // --- Goals ---

    public List<Goal> getGoals(String path) {
        return project_goals.getOrDefault(path, Collections.emptyList());
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
        project_goals.put(path, goals);
        db.commit();
        notifyListeners("goals:" + path, goals);
    }

    // --- Agent Stats ---

    public void saveAgentStat(AgentStat stat) {
        agent_stats.add(stat);
        db.commit();
        notifyListeners("agent_stats", getAgentStats());
    }

    public List<AgentStat> getAgentStats() {
        return new ArrayList<>(agent_stats);
    }

    // --- Agent Configs ---

    public AgentConfig getAgentConfigs(String agentName) {
        return agent_configs.get(agentName);
    }

    public List<AgentConfig> getAgentConfigs(String agentName, String projectPath) {
        AgentConfig config = agent_configs.get(agentName);
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
        agent_configs.put(agentName, config);
        db.commit();
        notifyListeners("agent_config:" + agentName, config);
    }

    public void deleteAgentConfig(String agentName) {
        agent_configs.remove(agentName);
        db.commit();
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
        return new HashMap<>(agent_configs);
    }

    // --- Ollama Servers ---

    public List<String> getOllamaServers() {
        return ollama_servers.getOrDefault("list", Collections.emptyList());
    }

    public void saveOllamaServers(List<String> servers) {
        ollama_servers.put("list", servers);
        db.commit();
        notifyListeners("ollama_servers", servers);
    }

    public String getSelectedOllamaServer() {
        return selected_ollama_server.getOrDefault("url", "");
    }

    public void saveSelectedOllamaServer(String url) {
        selected_ollama_server.put("url", url);
        db.commit();
        notifyListeners("selected_ollama_server", url);
    }

    // --- MCP Servers ---

    public List<McpServer> getMcpServers() {
        return mcp_servers.getOrDefault("all", Collections.emptyList());
    }

    public void saveMcpServers(List<McpServer> servers) {
        mcp_servers.put("all", servers);
        db.commit();
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
        if (key.startsWith("memory:")) {
            memories.put(key.substring(7), (String) value);
        } else if (key.startsWith("goals:")) {
            project_goals.put(key.substring(6), (List<Goal>) value);
        } else if (key.equals("mcp_servers")) {
            mcp_servers.put("all", (List<McpServer>) value);
        } else if (key.equals("agent_stats")) {
            agent_stats.clear();
            agent_stats.addAll((List<AgentStat>) value);
        } else if (key.startsWith("agent_config:")) {
            agent_configs.put(key.substring(13), (AgentConfig) value);
        } else if (key.equals("ollama_servers")) {
            ollama_servers.put("list", (List<String>) value);
        } else if (key.equals("selected_ollama_server")) {
            selected_ollama_server.put("url", (String) value);
        }
        db.commit();
    }
}
