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
        try {
            RunnerType rType = this.currentRunnerType.get();
            System.out.println("\n\u001b[33mRebuilding active runner for mode: " + rType + "...\u001b[0m");

            // 1. Close active closeable services to prevent resource locks (MapDB lock files, etc.)
            if (this.sessionService != null) {
                try {
                    java.lang.reflect.Method closeMethod = this.sessionService.getClass().getMethod("close");
                    closeMethod.invoke(this.sessionService);
                } catch (Exception e) {
                    if (this.sessionService instanceof AutoCloseable) {
                        try { ((AutoCloseable) this.sessionService).close(); } catch (Exception ex) {}
                    }
                }
            }
            if (this.artifactService != null) {
                try {
                    java.lang.reflect.Method closeMethod = this.artifactService.getClass().getMethod("close");
                    closeMethod.invoke(this.artifactService);
                } catch (Exception e) {
                    if (this.artifactService instanceof AutoCloseable) {
                        try { ((AutoCloseable) this.artifactService).close(); } catch (Exception ex) {}
                    }
                }
            }
            if (this.vectorStore instanceof MapDBVectorStore) {
                try { ((MapDBVectorStore) this.vectorStore).close(); } catch (Exception e) {}
            }

            // 2. Re-create base services matching current RunnerType
            Path projectPath = com.mkpro.utils.PathUtils.getProjectPath();
            Path mkproDir = projectPath.resolve(".mkpro");
            try {
                com.mkpro.utils.PathUtils.ensureDirectoriesExist(mkproDir.resolve("dummy"));
            } catch (Exception e) {}

            // Resolve instance-specific DB prefix (same logic as BootstrapService)
            String dbBaseName = System.getProperty("mkpro.db.name", null);
            if (dbBaseName == null || dbBaseName.isBlank()) {
                // Strip the random UUID suffix that was appended during bootstrap
                String rawName = this.instanceName;
                if (rawName != null && rawName.contains("-")) {
                    // instanceName format is "<name>-<4char-uuid>", extract the base
                    String basePart = rawName.substring(0, rawName.lastIndexOf('-'));
                    if (!basePart.isBlank()) {
                        dbBaseName = "mkpro_" + basePart;
                    }
                }
            }
            if (dbBaseName == null || dbBaseName.isBlank()) {
                dbBaseName = "mkpro_data";
            }

            if (rType == RunnerType.MAP_DB) {
                this.sessionService = new com.google.adk.sessions.MapDbSessionService(mkproDir.resolve(dbBaseName + "_sessions.db").toString());
                this.artifactService = new com.google.adk.artifacts.MapDbArtifactService(mkproDir.resolve(dbBaseName + "_artifacts.db").toString());
                MapDBVectorStore mvStore = new MapDBVectorStore(mkproDir.resolve(dbBaseName + "_vectors.db").toString(), "default");
                this.vectorStore = mvStore;
                this.memoryService = new com.google.adk.memory.MapDBMemoryService(mvStore, this.embeddingService);
            } else {
                this.sessionService = new com.google.adk.sessions.InMemorySessionService();
                this.artifactService = new com.google.adk.artifacts.InMemoryArtifactService();
                MapDBVectorStore mvStore = new MapDBVectorStore(mkproDir.resolve(dbBaseName + "_vectors.db").toString(), "default");
                this.vectorStore = mvStore;
                this.memoryService = new com.google.adk.memory.MapDBMemoryService(mvStore, this.embeddingService);
            }

            // Update configurations
            this.agentConfigs = this.centralMemory.getAllAgentConfigs();

            // Resolve active team file path specifically to avoid cross-loading all team config files
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

            // 3. Reconstruct AgentManager with specific active team file
            this.agentManager = new AgentManager(
                this.sessionService,
                this.artifactService,
                this.memoryService,
                this.apiKey,
                this.ollamaUrl,
                this.actionLogger,
                this.centralMemory,
                rType,
                teamFile, // Pass specific team config file instead of directory
                (MapDBVectorStore) this.vectorStore,
                this.embeddingService
            );

            // 4. Instantiate a new active runner
            this.runner = this.agentManager.createRunner(this.agentConfigs, "");

            // 5. Establish a clean default session in the new environment
            String sessionId = "default-session";
            com.google.adk.sessions.SessionKey sessionKey = new com.google.adk.sessions.SessionKey("mkpro", "Coordinator", sessionId);
            Session session = null;
            try {
                session = this.sessionService.getSession(sessionKey, com.google.adk.sessions.GetSessionConfig.builder().build()).blockingGet();
            } catch (Exception e) {
                // Ignore and proceed to create
            }
            if (session == null) {
                session = this.sessionService.createSession(sessionKey, new java.util.HashMap<>()).blockingGet();
            }
            this.currentSession = session;

            System.out.println("\u001b[32mSuccessfully rebuilt active runner & session context.\u001b[0m");
        } catch (Exception e) {
            System.err.println("Fatal: Rebuild sequence failed - " + e.getMessage());
            e.printStackTrace();
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
