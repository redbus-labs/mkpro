package com.mkpro.core;

import com.google.adk.artifacts.MapDbArtifactService;
import com.google.adk.memory.MapDBMemoryService;
import com.google.adk.memory.ZeroEmbeddingService;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.MapDbSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.EmbeddingService;
import com.google.adk.memory.MapDBVectorStore;
import com.mkpro.CentralMemory;
import com.mkpro.infra.network.discovery.DiscoveryService;
import com.mkpro.infra.network.messaging.P2PMessageBus;
import com.mkpro.infra.network.sync.SyncEngine;
import com.mkpro.LogHttpServer;
import com.mkpro.SimpleWebSocketServer;
import com.mkpro.ActionLogger;
import com.mkpro.config.ConfigService;
import com.mkpro.models.RunnerType;
import com.mkpro.utils.PathUtils;
import com.mkpro.agents.AgentManager;
import com.mkpro.InstanceRegistry;
import com.mkpro.graph.MapDbGraphRepository;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.mkpro.MkPro.*;

public class BootstrapService {

    public MkProContext bootstrap(String[] args) {
        // Suppress noisy JUL loggers
        java.util.logging.Logger.getLogger("io.opentelemetry").setLevel(java.util.logging.Level.OFF);
        java.util.logging.Logger.getLogger("org.mapdb").setLevel(java.util.logging.Level.OFF);
        java.util.logging.Logger.getLogger("org.mapdb.volume").setLevel(java.util.logging.Level.OFF);
        java.util.logging.Logger.getLogger("io.grpc").setLevel(java.util.logging.Level.OFF);
        java.util.logging.Logger.getLogger("com.google.genai").setLevel(java.util.logging.Level.OFF);
        java.util.logging.Logger.getLogger("com.google.adk").setLevel(java.util.logging.Level.WARNING);

        printBanner();

        MkProContext context = new MkProContext();
        parseArgs(args, context);

        if (context.getCurrentRunnerType().get() == null) {
            context.getCurrentRunnerType().set(promptForRunnerType());
        }

        initializeServices(context);
        setupTerminal(context);
        
        registerShutdownHook(context);

        return context;
    }

    private void registerShutdownHook(MkProContext context) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n" + ANSI_YELLOW + "Shutting down MkPro..." + ANSI_RESET);

            // Auto-save Markov model with live learning from this session
            if (context.getMarkovRouter() != null) {
                try {
                    java.nio.file.Path mkproDir = com.mkpro.utils.PathUtils.getProjectPath().resolve(".mkpro");
                    java.nio.file.Path modelPath = mkproDir.resolve("markov_model.dat");
                    context.getMarkovRouter().save(modelPath);
                } catch (Exception e) { /* Silent */ }
            }

            // Auto-export session logs as training data for next startup
            try {
                java.util.List<String> logs = ActionLogger.getLogs();
                if (logs.size() > 5) { // Only export if meaningful interaction happened
                    java.nio.file.Path dataDir = com.mkpro.utils.PathUtils.getProjectPath().resolve("datajsonl");
                    java.nio.file.Files.createDirectories(dataDir);
                    String timestamp = java.time.LocalDateTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                    java.nio.file.Path exportFile = dataDir.resolve("session_auto_" + timestamp + ".jsonl");
                    
                    // Quick export: extract USER→Coordinator pairs from logs
                    exportSessionLogs(logs, exportFile);
                }
            } catch (Exception e) { /* Silent */ }

            if (context.getDiscoveryService() != null) {
                context.getDiscoveryService().stop();
            }

            if (context.getP2pMessageBus() != null) {
                try {
                    context.getP2pMessageBus().stop();
                } catch (Exception e) {
                    // Ignore
                }
            }
            
            ActionLogger.shutdown();

            // Stop knowledge scheduler
            if (context.getKnowledgeScheduler() != null) {
                try {
                    context.getKnowledgeScheduler().stop();
                } catch (Throwable e) { /* Ignore */ }
            }
            
            if (context.getSessionService() instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) context.getSessionService()).close();
                } catch (Throwable e) {
                    // Ignore — classes may be unloaded during shutdown
                }
            }

            if (context.getArtifactService() instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) context.getArtifactService()).close();
                } catch (Throwable e) {
                    // Ignore — classes may be unloaded during shutdown
                }
            }

            if (context.getVectorStore() instanceof MapDBVectorStore) {
                try {
                    ((MapDBVectorStore) context.getVectorStore()).close();
                } catch (Throwable e) {
                    // Ignore — MapDB's CleanerUtil may be unloaded during shutdown
                }
            }

            if (context.getCentralMemory() != null) {
                try {
                    context.getCentralMemory().close();
                } catch (Throwable e) {
                    // Ignore — MapDB may be partially unloaded during shutdown
                }
            }
        }));
    }

    private void initKnowledgeScheduler(MkProContext context) {
        System.out.println(ANSI_BLUE + "[Knowledge] Initializing scheduler..." + ANSI_RESET);

        var knowledgeStore = new com.mkpro.knowledge.KnowledgeStore(context.getCentralMemory());
        var topicIndex = new com.mkpro.knowledge.TopicIndex();
        var fetcher = new com.mkpro.knowledge.SourceFetcher();

        // Load topic configs from schedules.yaml
        java.util.List<com.mkpro.knowledge.TopicConfig> topics = loadSchedulesConfig();

        if (topics.isEmpty()) {
            System.out.println(ANSI_YELLOW + "[Knowledge] No topics configured in schedules.yaml. Scheduler idle." + ANSI_RESET);
        } else {
            System.out.println(ANSI_GREEN + "[Knowledge] Loaded " + topics.size() + " topic(s) from schedules.yaml" + ANSI_RESET);
        }

        var scheduler = new com.mkpro.knowledge.KnowledgeScheduler(knowledgeStore, topicIndex, fetcher, topics);

        // The analyze callback uses the ADK runner for real LLM analysis
        // Wrapped with scheduler context flag to prevent circular knowledge requests
        scheduler.setAnalyzeCallback((topicName, prompt) -> {
            com.mkpro.knowledge.RequestKnowledgeTool.enterSchedulerContext();
            try {
                return analyzeWithRunner(context, topicName, prompt);
            } finally {
                com.mkpro.knowledge.RequestKnowledgeTool.exitSchedulerContext();
            }
        });

        // Initialize RequestKnowledgeTool
        com.mkpro.knowledge.RequestKnowledgeTool.init(scheduler, knowledgeStore);

        // Rebuild index from existing reports
        for (var report : knowledgeStore.getAllReports()) {
            if (report.getSummary() != null && !report.getSummary().isBlank()) {
                topicIndex.indexTopic(report.getName(), report.getSummary());
            }
        }
        topicIndex.rebuildIdf();

        context.setKnowledgeStore(knowledgeStore);
        context.setTopicIndex(topicIndex);
        context.setKnowledgeScheduler(scheduler);

        if (!topics.isEmpty()) {
            scheduler.start();
            System.out.println(ANSI_GREEN + "[Knowledge] Scheduler started." + ANSI_RESET);
        }
    }

    /**
     * Run LLM analysis for a knowledge topic via the ADK runner.
     * Extracted to keep the callback clean and enable scheduler context wrapping.
     */
    private String analyzeWithRunner(MkProContext context, String topicName, String prompt) {
        if (context.getRunner() == null || context.getCurrentSession() == null) {
            System.out.println(ANSI_YELLOW + "[Knowledge] Runner not available for " + topicName + ", storing raw data" + ANSI_RESET);
            return prompt.length() > 2000 ? prompt.substring(0, 2000) : prompt;
        }

        try {
            com.google.genai.types.Content message = com.google.genai.types.Content.fromParts(
                new com.google.genai.types.Part[]{com.google.genai.types.Part.fromText(prompt)});

            StringBuilder responseText = new StringBuilder();
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<String> errorRef = new java.util.concurrent.atomic.AtomicReference<>();

            context.getRunner().runAsync(context.getCurrentSession().sessionKey(), message)
                .blockingSubscribe(
                    event -> {
                        event.content().ifPresent(content -> {
                            content.parts().ifPresent(parts -> {
                                for (com.google.genai.types.Part part : parts) {
                                    part.text().ifPresent(responseText::append);
                                }
                            });
                        });
                    },
                    error -> {
                        errorRef.set(error.getMessage());
                        latch.countDown();
                    },
                    latch::countDown
                );

            latch.await(120, java.util.concurrent.TimeUnit.SECONDS);

            if (errorRef.get() != null) {
                System.out.println(ANSI_YELLOW + "[Knowledge] Analysis error for " + topicName + ": " + errorRef.get() + ANSI_RESET);
                return null;
            }

            String result = responseText.toString().trim();
            if (result.isEmpty()) {
                return null;
            }

            System.out.println(ANSI_GREEN + "[Knowledge] Analysis complete for " + topicName + " (" + result.length() + " chars)" + ANSI_RESET);
            return result;

        } catch (Exception e) {
            System.out.println(ANSI_YELLOW + "[Knowledge] Analysis failed for " + topicName + ": " + e.getMessage() + ANSI_RESET);
            return null;
        }
    }

    private java.util.List<com.mkpro.knowledge.TopicConfig> loadSchedulesConfig() {
        java.util.List<com.mkpro.knowledge.TopicConfig> topics = new java.util.ArrayList<>();

        // Look for schedules.yaml in multiple locations (priority order):
        // 1. .mkpro/schedules.yaml (project-local)
        // 2. ~/Documents/mkpro/schedules.yaml (user-global)
        Path[] searchPaths = {
            Paths.get(".mkpro", "schedules.yaml"),
            Paths.get(System.getProperty("user.home"), "Documents", "mkpro", "schedules.yaml")
        };

        Path configPath = null;
        for (Path p : searchPaths) {
            if (Files.exists(p)) {
                configPath = p;
                break;
            }
        }

        if (configPath == null) {
            // No schedules.yaml found — copy template from bundled resources
            try {
                java.io.InputStream templateStream = getClass().getClassLoader()
                    .getResourceAsStream("schedules_template.yaml");
                if (templateStream != null) {
                    Path targetDir = Paths.get(".mkpro");
                    Files.createDirectories(targetDir);
                    Path targetPath = targetDir.resolve("schedules.yaml");
                    Files.copy(templateStream, targetPath);
                    templateStream.close();
                    System.out.println(ANSI_GREEN + "[Knowledge] Created .mkpro/schedules.yaml from template. Edit it to configure your topics." + ANSI_RESET);
                    configPath = targetPath;
                }
            } catch (Exception e) {
                // Silent — template copy is best-effort
            }
        }

        if (configPath == null) {
            return topics;
        }

        try {
            com.fasterxml.jackson.databind.ObjectMapper yamlMapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());

            com.fasterxml.jackson.databind.JsonNode root = yamlMapper.readTree(configPath.toFile());
            com.fasterxml.jackson.databind.JsonNode topicsNode = root.get("topics");

            if (topicsNode != null && topicsNode.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode node : topicsNode) {
                    com.mkpro.knowledge.TopicConfig tc = new com.mkpro.knowledge.TopicConfig();
                    tc.setName(node.has("name") ? node.get("name").asText() : null);
                    tc.setTitle(node.has("title") ? node.get("title").asText() : tc.getName());

                    if (node.has("sources") && node.get("sources").isArray()) {
                        java.util.List<String> sources = new java.util.ArrayList<>();
                        for (com.fasterxml.jackson.databind.JsonNode s : node.get("sources")) {
                            sources.add(s.asText());
                        }
                        tc.setSources(sources);
                    }

                    if (node.has("instruction")) tc.setInstruction(node.get("instruction").asText());
                    if (node.has("agent")) tc.setAgent(node.get("agent").asText());
                    if (node.has("refreshIntervalMinutes")) tc.setRefreshIntervalMinutes(node.get("refreshIntervalMinutes").asInt());

                    if (tc.getName() != null && tc.getSources() != null && !tc.getSources().isEmpty()) {
                        topics.add(tc);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println(ANSI_YELLOW + "[Knowledge] Error loading schedules.yaml: " + e.getMessage() + ANSI_RESET);
        }

        return topics;
    }

    private void parseArgs(String[] args, MkProContext context) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("-v".equalsIgnoreCase(arg) || "--verbose".equalsIgnoreCase(arg)) {
                context.setVerbose(true);
            } else if ("-r".equalsIgnoreCase(arg) || "--runner".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    try {
                        context.getCurrentRunnerType().set(RunnerType.valueOf(args[i + 1].toUpperCase()));
                    } catch (IllegalArgumentException e) {
                        System.err.println("Invalid runner type: " + args[i+1]);
                    }
                    i++;
                }
            } else if ("--name".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    context.setInstanceName(args[i+1]);
                    i++;
                }
            } else if ("--no-network".equalsIgnoreCase(arg)) {
                context.setNetworkEnabled(false);
            } else if ("--network".equalsIgnoreCase(arg)) {
                context.setNetworkEnabled(true);
            } else if ("--scheduler".equalsIgnoreCase(arg)) {
                context.setSchedulerEnabled(true);
            }
        }
    }

    private RunnerType promptForRunnerType() {
        System.out.println(ANSI_BLUE + "Select Execution Runner:" + ANSI_RESET);
        System.out.println(ANSI_BRIGHT_GREEN + "[1] IN_MEMORY (Default, fast, ephemeral)" + ANSI_RESET);
        System.out.println(ANSI_BRIGHT_GREEN + "[2] MAP_DB (Persistent file-based)" + ANSI_RESET);
        System.out.println(ANSI_BRIGHT_GREEN + "[3] POSTGRES (Persistent relational DB)" + ANSI_RESET);
        System.out.print(ANSI_BLUE + "Enter selection [1]: " + ANSI_YELLOW);
        
        Scanner scanner = new Scanner(System.in);
        String choice = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        System.out.print(ANSI_RESET);
        
        if ("2".equals(choice)) return RunnerType.MAP_DB;
        if ("3".equals(choice)) return RunnerType.POSTGRES;
        return RunnerType.IN_MEMORY;
    }

    private void initializeServices(MkProContext context) {
        try {
            RunnerType runnerType = context.getCurrentRunnerType().get();
            
            Path projectPath = PathUtils.getProjectPath();
            Path mkproDir = projectPath.resolve(".mkpro");
            PathUtils.ensureDirectoriesExist(mkproDir.resolve("dummy"));

            // Initialize security: PathValidator with project root as primary safe directory
            com.mkpro.security.PathValidator.initialize(projectPath, java.util.List.of(mkproDir));

            // Resolve instance-specific DB prefix: --name arg > -Dmkpro.db.name > default "mkpro_data"
            String dbBaseName = resolveDbBaseName(context);

            // Initialize Embedding Service
            EmbeddingService embeddingService = new ZeroEmbeddingService(1536);
            context.setEmbeddingService(embeddingService);

            // Initialize Storage Services based on RunnerType
            // Each instance gets its own session/artifact/vector/log DBs — no sharing.
            if (runnerType == RunnerType.MAP_DB) {
                context.setSessionService(new MapDbSessionService(mkproDir.resolve(dbBaseName + "_sessions.db").toString()));
                context.setArtifactService(new MapDbArtifactService(mkproDir.resolve(dbBaseName + "_artifacts.db").toString()));
                MapDBVectorStore vectorStore = new MapDBVectorStore(mkproDir.resolve(dbBaseName + "_vectors.db").toString(), "default");
                context.setVectorStore(vectorStore);
                context.setMemoryService(new MapDBMemoryService(vectorStore, embeddingService));
            } else {
                context.setSessionService(new InMemorySessionService());
                context.setArtifactService(new InMemoryArtifactService());
                MapDBVectorStore vectorStore = new MapDBVectorStore(mkproDir.resolve(dbBaseName + "_vectors.db").toString(), "default");
                context.setVectorStore(vectorStore);
                context.setMemoryService(new MapDBMemoryService(vectorStore, embeddingService));
            }

            context.setCentralMemory(new CentralMemory());
            
            // Seed default public MCP servers on first run (safe, no data leakage)
            seedDefaultMcpServers(context.getCentralMemory());

            // Initialize Groovy script engine with CentralMemory backing
            com.mkpro.scripting.ScriptTools.init(context.getCentralMemory());

            // Initialize Knowledge Scheduler if --scheduler flag is set
            if (context.isSchedulerEnabled()) {
                initKnowledgeScheduler(context);
            } else {
                // Still set up store and index for /know command (search existing data)
                var knowledgeStore = new com.mkpro.knowledge.KnowledgeStore(context.getCentralMemory());
                var topicIndex = new com.mkpro.knowledge.TopicIndex();
                context.setKnowledgeStore(knowledgeStore);
                context.setTopicIndex(topicIndex);
                // Rebuild index from existing reports
                for (var report : knowledgeStore.getAllReports()) {
                    if (report.getSummary() != null && !report.getSummary().isBlank()) {
                        topicIndex.indexTopic(report.getName(), report.getSummary());
                    }
                }
                topicIndex.rebuildIdf();
            }

            // Networking Initialization
            P2PMessageBus p2pBus = null;
            String instanceId;
            String userName = System.getProperty("user.name", "user");
            instanceId = (context.getInstanceName() != null ? context.getInstanceName() : userName) + "-" + UUID.randomUUID().toString().substring(0, 4);
            context.setInstanceName(instanceId);

            if (context.isNetworkEnabled()) {
                int p2pPort = PathUtils.findAvailablePort(9000);
                p2pBus = new P2PMessageBus(p2pPort);
                p2pBus.start();
                context.setP2pMessageBus(p2pBus);

                DiscoveryService discoveryService = new DiscoveryService();
                discoveryService.setMessageBus(p2pBus);
                discoveryService.start(p2pPort, instanceId);
                context.setDiscoveryService(discoveryService);

                String memoryDbPath = PathUtils.getBaseDocumentsPath().resolve("memory_graph.db").toString();
                MapDbGraphRepository graphRepository = new MapDbGraphRepository(memoryDbPath);
                SyncEngine syncEngine = new SyncEngine(p2pBus, context.getCentralMemory(), (MapDbGraphRepository) graphRepository);
                context.setSyncEngine(syncEngine);
            } else {
                System.out.println("\u001b[33m[Network] Disabled (--no-network). Running in standalone mode.\u001b[0m");
            }

            context.setActionLogger(new ActionLogger(mkproDir.resolve(dbBaseName + "_logs.db").toString()));
            
            ConfigService configService = new ConfigService();
            Path teamsDir = ConfigService.setupTeamsDir();
            context.setTeamsDir(teamsDir);
            
            String geminiKey = configService.getSetting(ConfigService.PROP_GEMINI_KEY, null);
            String ollamaUrl = configService.getSetting(ConfigService.PROP_OLLAMA_URL, "http://localhost:11434");
            context.setApiKey(geminiKey);
            context.setOllamaUrl(ollamaUrl);

            String teamName = configService.getSetting(ConfigService.PROP_TEAM, "default");
            Path teamFile = teamsDir.resolve(teamName);
            if (!Files.exists(teamFile)) {
                if (Files.exists(teamsDir.resolve(teamName + ".yaml"))) {
                    teamFile = teamsDir.resolve(teamName + ".yaml");
                } else if (Files.exists(teamsDir.resolve(teamName + ".yml"))) {
                    teamFile = teamsDir.resolve(teamName + ".yml");
                } else {
                    teamName = "default";
                    teamFile = teamsDir.resolve("default.yaml");
                }
            }
            context.getCurrentTeam().set(teamName);

            // Construct AgentManager specifically with the active team file instead of the directory
            AgentManager am = new AgentManager(
                context.getSessionService(),
                context.getArtifactService(),
                context.getMemoryService(),
                context.getApiKey(),
                context.getOllamaUrl(),
                context.getActionLogger(),
                context.getCentralMemory(),
                context.getCurrentRunnerType().get(),
                teamFile, // Pass the resolved teamFile path directly to avoid cross-loading all team yaml configs
                (MapDBVectorStore) context.getVectorStore(),
                context.getEmbeddingService()
            );
            context.setAgentManager(am);
            context.setAgentConfigs(context.getCentralMemory().getAllAgentConfigs());

            // Register peer communication tools only if networking is enabled
            if (context.isNetworkEnabled() && p2pBus != null) {
                am.getToolRegistry().registerPeerAgentTool(p2pBus, instanceId);

                // Wire PeerAgentRequestHandler to process incoming AGENT_REQUEST messages
                com.mkpro.infra.network.peer.PeerAgentRequestHandler peerHandler = 
                    new com.mkpro.infra.network.peer.PeerAgentRequestHandler(p2pBus, am, context.getAgentConfigs(), instanceId);
                
                // Build PeerHandshake for exchanging project info on connection
                java.util.List<String> agentNames = new java.util.ArrayList<>(am.getAgentDefinitions().keySet());
                com.mkpro.models.AgentConfig coordConfig = context.getAgentConfigs().get("Coordinator");
                String primaryModel = coordConfig != null ? coordConfig.getModelName() : "unknown";
                com.mkpro.infra.network.peer.PeerHandshake peerHandshake = 
                    new com.mkpro.infra.network.peer.PeerHandshake(p2pBus, instanceId, agentNames, primaryModel, context.getCentralMemory());

                // Route messages by type
                com.mkpro.infra.network.sync.SyncEngine finalSyncEngine = context.getSyncEngine();
                p2pBus.setMessageHandler(msg -> {
                    String type = msg.has("type") ? msg.get("type").asText() : "";
                    switch (type) {
                        case "AGENT_REQUEST":
                            peerHandler.handleRequest(msg);
                            break;
                        case "PEER_HELLO":
                            com.mkpro.infra.network.peer.PeerHandshake.handleHello(msg);
                            break;
                        default:
                            if (finalSyncEngine != null) finalSyncEngine.processIncomingMessage(msg);
                            break;
                    }
                });

                // Send PEER_HELLO on every new connection
                p2pBus.setOnConnectHook(peerHandshake::sendHello);
            }

            Runner runner = am.createRunner(context.getAgentConfigs(), "");
            context.setRunner(runner);

            // Initialize Markov Router — auto-trains from datajsonl/ if available
            com.mkpro.routing.MarkovRouter markovRouter = new com.mkpro.routing.MarkovRouter();
            java.nio.file.Path markovModelPath = mkproDir.resolve("markov_model.dat");
            
            if (java.nio.file.Files.exists(markovModelPath)) {
                // Load user's existing model
                try {
                    markovRouter.load(markovModelPath);
                    // If model is empty, replace with bundled default
                    if (markovRouter.getTotalObservations() == 0) {
                        try (java.io.InputStream is = getClass().getResourceAsStream("/markov_model_default.dat")) {
                            if (is != null) {
                                java.nio.file.Files.copy(is, markovModelPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                markovRouter.load(markovModelPath);
                                System.out.println(ANSI_GREEN + "Markov Router: replaced empty model with bundled default (" + markovRouter.getTotalObservations() + " observations)." + ANSI_RESET);
                            }
                        } catch (Exception ex) { /* Silent */ }
                    }
                } catch (Exception e) {
                    System.out.println(ANSI_YELLOW + "Markov Router: model file corrupted, will retrain." + ANSI_RESET);
                }
            } else {
                // No local model — copy bundled default from resources
                try (java.io.InputStream is = getClass().getResourceAsStream("/markov_model_default.dat")) {
                    if (is != null) {
                        java.nio.file.Files.createDirectories(markovModelPath.getParent());
                        java.nio.file.Files.copy(is, markovModelPath);
                        markovRouter.load(markovModelPath);
                        System.out.println(ANSI_GREEN + "Markov Router: loaded bundled default model (" + markovRouter.getTotalObservations() + " observations)." + ANSI_RESET);
                    }
                } catch (Exception e) {
                    System.out.println(ANSI_YELLOW + "Markov Router: could not load bundled model: " + e.getMessage() + ANSI_RESET);
                }
            }
            
            java.nio.file.Path dataDir = projectPath.resolve("datajsonl");
            if (java.nio.file.Files.isDirectory(dataDir)) {
                int trained = com.mkpro.routing.MarkovTrainer.trainFromDirectory(markovRouter, dataDir);
                
                // Also train Maker completion patterns
                java.nio.file.Path makerSeqFile = dataDir.resolve("maker_sequences.jsonl");
                int makerTrained = com.mkpro.routing.MarkovTrainer.trainMakerSequences(markovRouter, makerSeqFile);

                if (trained > 0 || makerTrained > 0) {
                    System.out.println(ANSI_GREEN + "Markov Router: " + trained + " routing + " + makerTrained + " completion patterns (" + markovRouter.getTotalObservations() + " total)" + ANSI_RESET);
                    try { markovRouter.save(markovModelPath); } catch (Exception e) { /* Silent */ }
                } else {
                    System.out.println(ANSI_YELLOW + "Markov Router: no training data found in datajsonl/" + ANSI_RESET);
                }
            } else {
                System.out.println(ANSI_YELLOW + "Markov Router: datajsonl/ not found. Fast-routing disabled until data is available." + ANSI_RESET);
            }
            context.setMarkovRouter(markovRouter);

            // Initialize Maker Loop (goal-driven supervisor)
            com.mkpro.routing.MakerLoop makerLoop = new com.mkpro.routing.MakerLoop(markovRouter);
            context.setMakerLoop(makerLoop);

            String sessionId = "default-session";
            SessionKey sessionKey = new SessionKey("mkpro", "Coordinator", sessionId);
            Session session = null;
            try {
                session = context.getSessionService().getSession(sessionKey, com.google.adk.sessions.GetSessionConfig.builder().build()).blockingGet();
            } catch (Exception e) {}
            if (session == null) {
                session = context.getSessionService().createSession(sessionKey, new java.util.HashMap<>()).blockingGet();
            }
            context.setCurrentSession(session);

            // Show session resume summary if there's history
            if (session.events() != null && !session.events().isEmpty()) {
                int eventCount = session.events().size();
                // Count user messages
                long userMsgs = session.events().stream()
                    .filter(e -> "user".equals(e.author()))
                    .count();
                System.out.println(ANSI_CYAN + "── Session resumed (" + userMsgs + " exchanges, " + eventCount + " events). Use /history to review. ──" + ANSI_RESET);
                
                // Show last 3 exchanges
                var events = session.events();
                int shown = 0;
                for (int i = events.size() - 1; i >= 0 && shown < 6; i--) {
                    var event = events.get(i);
                    if (event.content().isPresent()) {
                        StringBuilder textBuilder = new StringBuilder();
                        event.content().get().parts().ifPresent(parts -> {
                            for (var part : parts) {
                                part.text().ifPresent(textBuilder::append);
                            }
                        });
                        String text = textBuilder.toString();
                        if (!text.isBlank()) {
                            String role = "user".equals(event.author()) ? "You" : "AI";
                            String preview = text.length() > 100 ? text.substring(0, 100) + "..." : text;
                            preview = preview.replace("\n", " ").trim();
                            if (!preview.isEmpty()) {
                                System.out.println(ANSI_DIM + "  " + role + ": " + preview + ANSI_RESET);
                                shown++;
                            }
                        }
                    }
                }
                if (shown > 0) System.out.println(ANSI_CYAN + "──────────────────────────────────────────" + ANSI_RESET);
            }

            System.out.println(ANSI_BRIGHT_GREEN + "Services initialized successfully." + ANSI_RESET);
        } catch (Exception e) {
            System.err.println("Failed to initialize services: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void setupTerminal(MkProContext context) {
        try {
            Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .build();
            context.setTerminal(terminal);

            LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();
            context.setLineReader(reader);
        } catch (IOException e) {
            System.err.println("Could not initialize JLine terminal.");
        }
    }

    /**
     * Resolves the database file prefix for this instance.
     * Priority: -Dmkpro.db.name > --name CLI arg > default "mkpro_data".
     * This ensures each instance gets its own isolated storage files.
     */
    private String resolveDbBaseName(MkProContext context) {
        // 1. System property (set by shell scripts from script filename)
        String sysProp = System.getProperty("mkpro.db.name");
        if (sysProp != null && !sysProp.isBlank()) {
            return sysProp;
        }
        // 2. --name CLI argument (set during parseArgs)
        String instanceName = context.getInstanceName();
        if (instanceName != null && !instanceName.isBlank()) {
            return "mkpro_" + instanceName;
        }
        // 3. Default
        return "mkpro_data";
    }

    /**
     * Quick export of session logs to JSONL for Markov training.
     * Extracts USER→agent response pairs from ActionLogger entries.
     */
    private static void exportSessionLogs(java.util.List<String> logs, java.nio.file.Path outputFile) {
        try (java.io.BufferedWriter writer = java.nio.file.Files.newBufferedWriter(outputFile)) {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String lastUserMsg = null;

            for (String log : logs) {
                // Parse: [timestamp] ROLE: content
                int bracketEnd = log.indexOf(']');
                if (bracketEnd < 0) continue;
                String rest = log.substring(bracketEnd + 2); // Skip "] "
                int colonIdx = rest.indexOf(':');
                if (colonIdx < 0) continue;

                String role = rest.substring(0, colonIdx).trim();
                String content = rest.substring(colonIdx + 1).trim();

                if ("USER".equals(role)) {
                    lastUserMsg = content;
                } else if (lastUserMsg != null && !"INFO".equals(role) && !"SYSTEM".equals(role)) {
                    // This is an agent response to the last user message
                    com.fasterxml.jackson.databind.node.ObjectNode line = mapper.createObjectNode();
                    com.fasterxml.jackson.databind.node.ArrayNode messages = mapper.createArrayNode();
                    
                    com.fasterxml.jackson.databind.node.ObjectNode user = mapper.createObjectNode();
                    user.put("role", "user");
                    user.put("content", lastUserMsg);
                    messages.add(user);
                    
                    com.fasterxml.jackson.databind.node.ObjectNode assistant = mapper.createObjectNode();
                    assistant.put("role", "assistant");
                    assistant.put("content", content);
                    messages.add(assistant);
                    
                    line.set("messages", messages);
                    writer.write(mapper.writeValueAsString(line));
                    writer.newLine();
                    
                    lastUserMsg = null; // Consumed
                }
            }
        } catch (Exception e) {
            // Silent — don't let export failure block shutdown
        }
    }

    private void printBanner() {
        System.out.println(ANSI_CYAN + "========================================" + ANSI_RESET);
        System.out.println(ANSI_CYAN + "   __  __ _    ____  ____   ___ " + ANSI_RESET);
        System.out.println(ANSI_CYAN + "  |  \\/  | | _|  _ \\|  _ \\ / _ \\" + ANSI_RESET);
        System.out.println(ANSI_CYAN + "  | |\\/| | |/ / |_) | |_) | | | |" + ANSI_RESET);
        System.out.println(ANSI_CYAN + "  | |  | |   <|  __/|  _ <| |_| |" + ANSI_RESET);
        System.out.println(ANSI_CYAN + "  |_|  |_|_|\\_\\_|   |_| \\_\\\\___/ " + ANSI_RESET);
        System.out.println(ANSI_CYAN + "========================================" + ANSI_RESET);
        System.out.println(ANSI_YELLOW + " Multi-Agent Development Framework " + ANSI_RESET);
        System.out.println(ANSI_CYAN + "========================================" + ANSI_RESET);
    }

    /**
     * Seeds default public MCP servers on first run.
     * These are read-only services that provide documentation and web search — 
     * they do NOT receive your source code or project data.
     * All default servers start DISABLED; user must explicitly enable them via /mcp.
     */
    private void seedDefaultMcpServers(CentralMemory centralMemory) {
        if (!centralMemory.getMcpServers().isEmpty()) {
            return; // Already configured, don't overwrite
        }

        com.mkpro.models.McpServer context7 = new com.mkpro.models.McpServer(
            "Context7", "https://mcp.context7.com/mcp", com.mkpro.models.McpServer.McpType.API);
        context7.setEnabled(false); // Disabled by default — user opts in

        com.mkpro.models.McpServer sequentialThinking = new com.mkpro.models.McpServer(
            "SequentialThinking", "https://mcp-sequential-thinking.onrender.com/mcp", com.mkpro.models.McpServer.McpType.API);
        sequentialThinking.setEnabled(false);

        centralMemory.addMcpServer(context7);
        centralMemory.addMcpServer(sequentialThinking);

        // Ensure default Ollama endpoint (localhost) is always present
        java.util.List<String> ollamaServers = new java.util.ArrayList<>(centralMemory.getOllamaServers());
        boolean hasLocal = ollamaServers.stream().anyMatch(e -> e.startsWith("local|"));
        if (!hasLocal) {
            ollamaServers.add(0, "local|http://localhost:11434");
            centralMemory.saveOllamaServers(ollamaServers);
        }
    }
}
