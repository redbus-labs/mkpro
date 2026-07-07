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
            
            // Networking Initialization
            int p2pPort = PathUtils.findAvailablePort(9000);
            P2PMessageBus p2pBus = new P2PMessageBus(p2pPort);
            p2pBus.start();
            context.setP2pMessageBus(p2pBus);

            String userName = System.getProperty("user.name", "user");
            String instanceId = (context.getInstanceName() != null ? context.getInstanceName() : userName) + "-" + UUID.randomUUID().toString().substring(0, 4);
            context.setInstanceName(instanceId);

            DiscoveryService discoveryService = new DiscoveryService();
            discoveryService.start(p2pPort, instanceId);
            context.setDiscoveryService(discoveryService);

            String memoryDbPath = PathUtils.getBaseDocumentsPath().resolve("memory_graph.db").toString();
            MapDbGraphRepository graphRepository = new MapDbGraphRepository(memoryDbPath);
            SyncEngine syncEngine = new SyncEngine(p2pBus, context.getCentralMemory(), (MapDbGraphRepository) graphRepository);
            p2pBus.setMessageHandler(syncEngine::processIncomingMessage);
            context.setSyncEngine(syncEngine);

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

            Runner runner = am.createRunner(context.getAgentConfigs(), "");
            context.setRunner(runner);
            
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
}
