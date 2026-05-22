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
import com.mkpro.LogHttpServer;
import com.mkpro.SimpleWebSocketServer;
import com.mkpro.ActionLogger;
import com.mkpro.config.ConfigService;
import com.mkpro.models.RunnerType;
import com.mkpro.utils.PathUtils;
import com.mkpro.agents.AgentManager;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
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
            PathUtils.ensureDirectoriesExist(projectPath.resolve("dummy"));

            String dbBaseName = "mkpro_data";

            // Initialize Embedding Service
            EmbeddingService embeddingService = new ZeroEmbeddingService(1536);
            context.setEmbeddingService(embeddingService);

            // Initialize Storage Services based on RunnerType
            if (runnerType == RunnerType.MAP_DB) {
                context.setSessionService(new MapDbSessionService(projectPath.resolve(dbBaseName + "_sessions.db").toString()));
                context.setArtifactService(new MapDbArtifactService(projectPath.resolve(dbBaseName + "_artifacts.db").toString()));
                MapDBVectorStore vectorStore = new MapDBVectorStore(projectPath.resolve(dbBaseName + "_vectors.db").toString(), "default");
                context.setVectorStore(vectorStore);
                context.setMemoryService(new MapDBMemoryService(vectorStore, embeddingService));
            } else {
                context.setSessionService(new InMemorySessionService());
                context.setArtifactService(new InMemoryArtifactService());
                MapDBVectorStore vectorStore = new MapDBVectorStore(projectPath.resolve(dbBaseName + "_vectors_temp.db").toString(), "default");
                context.setVectorStore(vectorStore);
                context.setMemoryService(new MapDBMemoryService(vectorStore, embeddingService));
            }

            context.setCentralMemory(new CentralMemory());
            context.setDiscoveryService(new DiscoveryService());
            context.setActionLogger(new ActionLogger(projectPath.resolve(dbBaseName + "_logs.db").toString()));
            
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
            com.google.adk.sessions.SessionKey sessionKey = new com.google.adk.sessions.SessionKey("mkpro", "Coordinator", sessionId);
            Session session = null;

            try {
                session = context.getSessionService().getSession(sessionKey, com.google.adk.sessions.GetSessionConfig.builder().build()).blockingGet();
            } catch (Exception e) {
                System.err.println("Warning: Could not load existing session '" + sessionId + "'. Creating a new one.");
            }

            if (session == null) {
                session = context.getSessionService().createSession(sessionKey, new java.util.HashMap<>()).blockingGet();
            }
            context.setCurrentSession(session);

        } catch (Exception e) {
            System.err.println("Failed to initialize services: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void printBanner() {
        System.out.println(ANSI_BOLD + "========================================" + ANSI_RESET);
        System.out.println(ANSI_BOLD + "       Welcome to MkPro CLI             " + ANSI_RESET);
        System.out.println(ANSI_BOLD + "========================================" + ANSI_RESET);
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
            System.err.println("Failed to initialize terminal: " + e.getMessage());
        }
    }
}
