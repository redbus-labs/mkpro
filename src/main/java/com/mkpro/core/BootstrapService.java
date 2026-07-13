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
            
            if (context.getSessionService() instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) context.getSessionService()).close();
                } catch (Exception e) {
                    // Ignore
                }
            }

            if (context.getArtifactService() instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) context.getArtifactService()).close();
                } catch (Exception e) {
                    // Ignore
                }
            }

            if (context.getVectorStore() instanceof MapDBVectorStore) {
                try {
                    ((MapDBVectorStore) context.getVectorStore()).close();
                } catch (Exception e) {
                    // Ignore
                }
            }

            if (context.getCentralMemory() != null) {
                try {
                    context.getCentralMemory().close();
                } catch (Exception e) {
                    // Ignore
                }
            }
        }));
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
            try {
                markovRouter.load(markovModelPath);
            } catch (Exception e) { /* No saved model yet */ }
            
            java.nio.file.Path dataDir = projectPath.resolve("datajsonl");
            if (java.nio.file.Files.isDirectory(dataDir)) {
                int trained = com.mkpro.routing.MarkovTrainer.trainFromDirectory(markovRouter, dataDir);
                if (trained > 0) {
                    System.out.println(ANSI_GREEN + "Markov Router: trained on " + trained + " examples (" + markovRouter.getTotalObservations() + " total observations)" + ANSI_RESET);
                    try { markovRouter.save(markovModelPath); } catch (Exception e) { /* Silent */ }
                } else {
                    System.out.println(ANSI_YELLOW + "Markov Router: no training data found in datajsonl/" + ANSI_RESET);
                }
            } else {
                System.out.println(ANSI_YELLOW + "Markov Router: datajsonl/ not found. Fast-routing disabled until data is available." + ANSI_RESET);
            }
            context.setMarkovRouter(markovRouter);

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
