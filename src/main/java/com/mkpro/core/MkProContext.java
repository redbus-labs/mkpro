package com.mkpro.core;

import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.memory.BaseMemoryService;
import com.mkpro.CentralMemory;
import com.mkpro.infra.network.discovery.DiscoveryService;
import com.mkpro.infra.network.messaging.P2PMessageBus;
import com.mkpro.infra.network.sync.SyncEngine;
import com.mkpro.LogHttpServer;
import com.mkpro.SimpleWebSocketServer;
import com.mkpro.ActionLogger;
import com.mkpro.models.AgentConfig;
import com.mkpro.models.RunnerType;
import com.google.adk.memory.EmbeddingService;
import com.google.adk.memory.VectorStore;
import com.google.adk.memory.MapDBVectorStore;
import com.mkpro.config.ConfigService;
import com.mkpro.agents.AgentManager;
import org.jline.terminal.Terminal;
import org.jline.reader.LineReader;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MkProContext encapsulates the application state.
 */
public class MkProContext {
    private Runner runner;
    private Session currentSession;
    private BaseSessionService sessionService;
    private BaseArtifactService artifactService;
    private BaseMemoryService memoryService;
    private CentralMemory centralMemory;
    private DiscoveryService discoveryService;
    private P2PMessageBus p2pMessageBus;
    private SyncEngine syncEngine;
    private LogHttpServer logHttpServer;
    private SimpleWebSocketServer webSocketServer;
    private ActionLogger actionLogger;
    private Map<String, AgentConfig> agentConfigs = new HashMap<>();
    private AtomicReference<RunnerType> currentRunnerType = new AtomicReference<>(null);
    private AtomicReference<String> currentTeam = new AtomicReference<>("default");
    private Path teamsDir;
    private String apiKey;
    private String ollamaUrl = "http://localhost:11434"; // Added default
    private String instanceName;
    private boolean verbose;
    private Terminal terminal;
    private LineReader lineReader;
    private EmbeddingService embeddingService;
    private VectorStore vectorStore;
    private AtomicBoolean makerEnabled = new AtomicBoolean(false);
    private AgentManager agentManager;

    public MkProContext() {
    }

    public void rebuildRunner() {
        rebuildRunner(false);
    }

    /**
     * Rebuilds the active runner. Config/MCP/team changes only need an agent refresh;
     * runner-type switches must recreate storage services as well.
     */
    public void rebuildRunner(boolean rebuildStorage) {
        RunnerType rType = this.currentRunnerType.get();
        if (rType == null) {
            System.err.println("Cannot rebuild runner: no runner type is configured.");
            return;
        }

        Runner previousRunner = this.runner;
        Session previousSession = this.currentSession;
        AgentManager previousAgentManager = this.agentManager;

        BaseSessionService newSessionService = this.sessionService;
        BaseArtifactService newArtifactService = this.artifactService;
        BaseMemoryService newMemoryService = this.memoryService;
        VectorStore newVectorStore = this.vectorStore;
        boolean openedNewStorage = false;

        System.out.println("\n\u001b[33mRebuilding active runner for mode: " + rType + "...\u001b[0m");

        try {
            if (rebuildStorage) {
                Path mkproDir = com.mkpro.utils.PathUtils.resolveMkproDataDir(com.mkpro.utils.PathUtils.getProjectPath());
                String dbBaseName = "mkpro_data";

                if (rType == RunnerType.MAP_DB) {
                    newSessionService = new com.google.adk.sessions.MapDbSessionService(
                        mkproDir.resolve(dbBaseName + "_sessions.db").toString()
                    );
                    newArtifactService = new com.google.adk.artifacts.MapDbArtifactService(
                        mkproDir.resolve(dbBaseName + "_artifacts.db").toString()
                    );
                    MapDBVectorStore mvStore = new MapDBVectorStore(
                        mkproDir.resolve(dbBaseName + "_vectors.db").toString(), "default"
                    );
                    newVectorStore = mvStore;
                    newMemoryService = new com.google.adk.memory.MapDBMemoryService(mvStore, this.embeddingService);
                } else {
                    newSessionService = new com.google.adk.sessions.InMemorySessionService();
                    newArtifactService = new com.google.adk.artifacts.InMemoryArtifactService();
                    MapDBVectorStore mvStore = new MapDBVectorStore(
                        mkproDir.resolve(dbBaseName + "_vectors_temp.db").toString(), "default"
                    );
                    newVectorStore = mvStore;
                    newMemoryService = new com.google.adk.memory.MapDBMemoryService(mvStore, this.embeddingService);
                }
                openedNewStorage = true;
            } else if (this.sessionService == null || this.artifactService == null || this.memoryService == null) {
                throw new IllegalStateException("Storage services are not initialized.");
            }

            reloadRuntimeSettings();
            Map<String, AgentConfig> mergedConfigs = new HashMap<>(this.centralMemory.getAllAgentConfigs());
            if (this.agentConfigs != null) {
                mergedConfigs.putAll(this.agentConfigs);
            }
            this.agentConfigs = mergedConfigs;
            Path teamFile = resolveActiveTeamFile();

            AgentManager newAgentManager = new AgentManager(
                newSessionService,
                newArtifactService,
                newMemoryService,
                this.apiKey,
                this.ollamaUrl,
                this.actionLogger,
                this.centralMemory,
                rType,
                teamFile,
                (MapDBVectorStore) newVectorStore,
                this.embeddingService
            );

            Runner newRunner = newAgentManager.createRunner(this.agentConfigs, "");
            Session newSession = getOrCreateDefaultSession(newSessionService);
            if (newSession == null) {
                throw new IllegalStateException("Could not create or load the default session.");
            }

            if (rebuildStorage) {
                closeStorageServicesQuietly(this.sessionService, this.artifactService, this.vectorStore);
                this.sessionService = newSessionService;
                this.artifactService = newArtifactService;
                this.memoryService = newMemoryService;
                this.vectorStore = newVectorStore;
            }

            this.agentManager = newAgentManager;
            this.runner = newRunner;
            this.currentSession = newSession;

            System.out.println("\u001b[32mSuccessfully rebuilt active runner & session context.\u001b[0m");
        } catch (Exception e) {
            if (openedNewStorage) {
                closeStorageServicesQuietly(newSessionService, newArtifactService, newVectorStore);
            }

            this.runner = previousRunner;
            this.currentSession = previousSession;
            this.agentManager = previousAgentManager;

            System.err.println("Rebuild failed: " + e.getMessage());
            if (previousRunner != null && previousSession != null) {
                System.err.println("Previous runner and session were restored.");
            } else {
                System.err.println("No active runner is available. Check Gemini API key, team config, and .mkpro permissions.");
            }
            if (this.verbose) {
                e.printStackTrace();
            }
        }
    }

    private void reloadRuntimeSettings() {
        ConfigService configService = new ConfigService();
        String geminiKey = configService.getSetting(ConfigService.PROP_GEMINI_KEY, null);
        if (geminiKey != null && !geminiKey.isBlank()) {
            this.apiKey = geminiKey;
        }
        this.ollamaUrl = configService.getSetting(ConfigService.PROP_OLLAMA_URL, this.ollamaUrl);
    }

    private Path resolveActiveTeamFile() {
        Path teamFile = this.teamsDir;
        if (this.teamsDir != null && Files.isDirectory(this.teamsDir)) {
            String tName = this.currentTeam.get();
            Path resolved = this.teamsDir.resolve(tName);
            if (!Files.exists(resolved)) {
                if (Files.exists(this.teamsDir.resolve(tName + ".yaml"))) {
                    resolved = this.teamsDir.resolve(tName + ".yaml");
                } else if (Files.exists(this.teamsDir.resolve(tName + ".yml"))) {
                    resolved = this.teamsDir.resolve(tName + ".yml");
                }
            }
            if (Files.exists(resolved)) {
                teamFile = resolved;
            }
        }
        return teamFile;
    }

    private Session getOrCreateDefaultSession(BaseSessionService sessionService) {
        String sessionId = "default-session";
        com.google.adk.sessions.SessionKey sessionKey =
            new com.google.adk.sessions.SessionKey("mkpro", "Coordinator", sessionId);
        Session session = null;
        try {
            session = sessionService
                .getSession(sessionKey, com.google.adk.sessions.GetSessionConfig.builder().build())
                .blockingGet();
        } catch (Exception ignored) {
            // Fall through to create
        }
        if (session == null) {
            session = sessionService.createSession(sessionKey, new java.util.HashMap<>()).blockingGet();
        }
        return session;
    }

    private void closeStorageServicesQuietly(
        BaseSessionService sessionService,
        BaseArtifactService artifactService,
        VectorStore vectorStore
    ) {
        if (sessionService != null) {
            try {
                java.lang.reflect.Method closeMethod = sessionService.getClass().getMethod("close");
                closeMethod.invoke(sessionService);
            } catch (Exception e) {
                if (sessionService instanceof AutoCloseable) {
                    try {
                        ((AutoCloseable) sessionService).close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        if (artifactService != null) {
            try {
                java.lang.reflect.Method closeMethod = artifactService.getClass().getMethod("close");
                closeMethod.invoke(artifactService);
            } catch (Exception e) {
                if (artifactService instanceof AutoCloseable) {
                    try {
                        ((AutoCloseable) artifactService).close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        if (vectorStore instanceof MapDBVectorStore) {
            try {
                ((MapDBVectorStore) vectorStore).close();
            } catch (Exception ignored) {
            }
        }
    }

    public Runner getRunner() {
        return runner;
    }

    public void setRunner(Runner runner) {
        this.runner = runner;
    }

    public Session getCurrentSession() {
        return currentSession;
    }

    public void setCurrentSession(Session currentSession) {
        this.currentSession = currentSession;
    }

    public BaseSessionService getSessionService() {
        return sessionService;
    }

    public void setSessionService(BaseSessionService sessionService) {
        this.sessionService = sessionService;
    }

    public BaseArtifactService getArtifactService() {
        return artifactService;
    }

    public void setArtifactService(BaseArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    public BaseMemoryService getMemoryService() {
        return memoryService;
    }

    public void setMemoryService(BaseMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    public CentralMemory getCentralMemory() {
        return centralMemory;
    }

    public void setCentralMemory(CentralMemory centralMemory) {
        this.centralMemory = centralMemory;
    }

    public DiscoveryService getDiscoveryService() {
        return discoveryService;
    }

    public void setDiscoveryService(DiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    public P2PMessageBus getP2pMessageBus() {
        return p2pMessageBus;
    }

    public void setP2pMessageBus(P2PMessageBus p2pMessageBus) {
        this.p2pMessageBus = p2pMessageBus;
    }

    public SyncEngine getSyncEngine() {
        return syncEngine;
    }

    public void setSyncEngine(SyncEngine syncEngine) {
        this.syncEngine = syncEngine;
    }

    public LogHttpServer getLogHttpServer() {
        return logHttpServer;
    }

    public void setLogHttpServer(LogHttpServer logHttpServer) {
        this.logHttpServer = logHttpServer;
    }

    public SimpleWebSocketServer getWebSocketServer() {
        return webSocketServer;
    }

    public void setWebSocketServer(SimpleWebSocketServer webSocketServer) {
        this.webSocketServer = webSocketServer;
    }

    public ActionLogger getActionLogger() {
        return actionLogger;
    }

    public void setActionLogger(ActionLogger actionLogger) {
        this.actionLogger = actionLogger;
    }

    public Map<String, AgentConfig> getAgentConfigs() {
        return agentConfigs;
    }

    public void setAgentConfigs(Map<String, AgentConfig> agentConfigs) {
        this.agentConfigs = agentConfigs;
    }

    public AtomicReference<RunnerType> getCurrentRunnerType() {
        return currentRunnerType;
    }

    public void setCurrentRunnerType(AtomicReference<RunnerType> currentRunnerType) {
        this.currentRunnerType = currentRunnerType;
    }

    public AtomicReference<String> getCurrentTeam() {
        return currentTeam;
    }

    public void setCurrentTeam(AtomicReference<String> currentTeam) {
        this.currentTeam = currentTeam;
    }

    public Path getTeamsDir() {
        return teamsDir;
    }

    public void setTeamsDir(Path teamsDir) {
        this.teamsDir = teamsDir;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getOllamaUrl() {
        return ollamaUrl;
    }

    public void setOllamaUrl(String ollamaUrl) {
        this.ollamaUrl = ollamaUrl;
    }

    public String getInstanceName() {
        return instanceName;
    }

    public void setInstanceName(String instanceName) {
        this.instanceName = instanceName;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public Terminal getTerminal() {
        return terminal;
    }

    public void setTerminal(Terminal terminal) {
        this.terminal = terminal;
    }

    public LineReader getLineReader() {
        return lineReader;
    }

    public void setLineReader(LineReader lineReader) {
        this.lineReader = lineReader;
    }

    public EmbeddingService getEmbeddingService() {
        return embeddingService;
    }

    public void setEmbeddingService(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    public VectorStore getVectorStore() {
        return vectorStore;
    }

    public void setVectorStore(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public AtomicBoolean getMakerEnabled() {
        return makerEnabled;
    }

    public void setMakerEnabled(AtomicBoolean makerEnabled) {
        this.makerEnabled = makerEnabled;
    }

    public AgentManager getAgentManager() {
        return agentManager;
    }

    public void setAgentManager(AgentManager agentManager) {
        this.agentManager = agentManager;
    }
}
