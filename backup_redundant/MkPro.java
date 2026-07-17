package com.mkpro;

import com.mkpro.core.MkProContext;
import com.mkpro.core.BootstrapService;
import com.mkpro.ui.TerminalUI;
import com.mkpro.commands.CommandRegistry;
import com.mkpro.commands.impl.*;

public class MkPro {
    // ANSI Color Constants (Maintained for backward compatibility and shared usage)
    public static final String ANSI_RESET = "\u001b[0m";
    public static final String ANSI_BRIGHT_GREEN = "\u001b[92m";
    public static final String ANSI_LIGHT_ORANGE = "\u001b[38;5;214m";
    public static final String ANSI_YELLOW = "\u001b[33m";
    public static final String ANSI_BLUE = "\u001b[34m";
    public static final String ANSI_GREEN = "\u001b[32m";
    public static final String ANSI_RED = "\u001b[31m";
    public static final String ANSI_CYAN = "\u001b[36m";
    public static final String ANSI_DIM = "\u001b[90m";
    public static final String ANSI_PURPLE = "\u001b[35m";
    public static final String ANSI_LIGHT_PURPLE = "\u001b[38;5;177m";
    public static final String ANSI_WHITE = "\u001b[37m";
    public static final String ANSI_BRIGHT_MAGENTA = "\u001B[95m";
    public static final String ANSI_BOLD = "\u001b[1m";

    public static void main(String[] args) {
        try {
            // Suppress noisy JUL loggers FIRST — before any library class loading
            java.util.logging.LogManager.getLogManager().reset();
            java.util.logging.Logger rootLogger = java.util.logging.Logger.getLogger("");
            rootLogger.setLevel(java.util.logging.Level.WARNING);
            for (java.util.logging.Handler h : rootLogger.getHandlers()) {
                h.setLevel(java.util.logging.Level.WARNING);
            }
            
            // Filter MapDB's direct stderr writes
            java.io.PrintStream originalErr = System.err;
            System.setErr(new java.io.PrintStream(originalErr) {
                @Override
                public void println(String x) {
                    if (x != null && (x.contains("Registry lock error") || x.contains("ClosedChannelException"))) {
                        return; // Suppress
                    }
                    super.println(x);
                }
            });

            // 1. Bootstrap the application context
            BootstrapService bootstrapService = new BootstrapService();
            MkProContext context = bootstrapService.bootstrap(args);

            // 2. Start web server if --web flag is present
            int webPort = getWebPort(args);
            if (webPort > 0) {
                com.mkpro.web.WebChatServer webServer = new com.mkpro.web.WebChatServer(webPort);
                webServer.setCentralMemory(context.getCentralMemory());
                if (context.getKnowledgeStore() != null && context.getTopicIndex() != null) {
                    webServer.setKnowledgeComponents(context.getKnowledgeStore(), context.getTopicIndex());
                }
                webServer.start();
                context.setWebChatServer(webServer);
            }

            // 3. Initialize Command Registry
            CommandRegistry registry = new CommandRegistry();
            registerCommands(registry);
            
            // 4. Wire web input handler (web messages processed directly via runner in background thread)
            if (context.getWebChatServer() != null) {
                final com.mkpro.core.MkProContext ctx = context;
                context.getWebChatServer().setInputHandler(text -> {
                    // Process web input in a background thread
                    new Thread(() -> processWebInput(ctx, text), "web-input").start();
                });
            }

            // 5. Start the UI Loop
            TerminalUI ui = new TerminalUI(context, registry);
            ui.start();
        } catch (Exception e) {
            System.err.println("\n" + ANSI_RED + "FATAL ERROR during startup:" + ANSI_RESET);
            System.err.println(ANSI_YELLOW + e.getMessage() + ANSI_RESET);
            
            // In case of a wrapped exception, print the root cause if it's different
            if (e.getCause() != null && !e.getCause().getMessage().equals(e.getMessage())) {
                System.err.println(ANSI_RED + "Reason: " + ANSI_RESET + e.getCause().getMessage());
            }
            
            System.exit(1);
        }
    }

    private static void registerCommands(CommandRegistry registry) {
        registry.register(new StatusCommand());
        registry.register(new StatsCommand());
        registry.register(new McpCommand());
        registry.register(new IndexCommand());
        registry.register(new TeamCommand());
        registry.register(new RunnerCommand());
        registry.register(new ConfigCommand());
        registry.register(new ModelCommand());
        registry.register(new RememberCommand());
        registry.register(new ExportRunnerCommand());
        registry.register(new ExportTrainingDataCommand());
        registry.register(new TrainCommand());
        registry.register(new VisualizeCommand());
        registry.register(new NetworkCommand());
        registry.register(new CertCommand());
        registry.register(new OllamaCommand());
        registry.register(new HistoryCommand());
        registry.register(new KnowledgeCommand());
        registry.register(new HelpCommand(registry));
        registry.register(new ExitCommand());
        // /quit is an alias for /exit
        registry.register(new com.mkpro.commands.Command() {
            public void execute(String[] args, com.mkpro.core.MkProContext context) throws Exception { System.out.println("\u001b[33mGoodbye!\u001b[0m"); System.exit(0); }
            public String getName() { return "quit"; }
            public String getDescription() { return "Exit the application."; }
        });
        // Add others like ResetCommand, SummarizeCommand, etc.
    }

    /**
     * Process a /command from web and send results back.
     * Captures System.out during command execution and broadcasts to web.
     */
    private static void processWebCommand(com.mkpro.core.MkProContext context, String text, com.mkpro.web.WebChatServer web) {
        try {
            // Capture stdout during command execution
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.io.PrintStream captureStream = new java.io.PrintStream(baos, true, "UTF-8");
            java.io.PrintStream originalOut = System.out;
            
            System.setOut(captureStream);
            try {
                if (webRegistry == null) {
                    webRegistry = new CommandRegistry();
                    registerCommands(webRegistry);
                }
                boolean handled = webRegistry.executeCommand(text, context);
                if (!handled) {
                    System.out.println("Unknown command: " + text);
                }
            } finally {
                System.setOut(originalOut);
            }
            
            String output = baos.toString("UTF-8");
            if (!output.isEmpty()) {
                // Strip ANSI codes for web display
                String clean = output.replaceAll("\u001b\\[[0-9;]*m", "");
                web.broadcastStreamStart("System", "command");
                web.broadcastStreamChunk("```\n" + clean + "```");
                web.broadcastStreamEnd();
            }
            
            // Also print to terminal
            originalOut.print(ANSI_CYAN + "[Web] " + text + ANSI_RESET + "\n");
            originalOut.print(output);
        } catch (Exception e) {
            web.broadcastStreamStart("System", "error");
            web.broadcastStreamChunk("Error: " + e.getMessage());
            web.broadcastStreamEnd();
        }
    }

    /**
     * Parse --web [port] from command line args.
     * Returns port number if --web is present (default 8080), or -1 if not.
     */
    private static int getWebPort(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--web".equals(args[i])) {
                // Check if next arg is a port number
                if (i + 1 < args.length) {
                    try {
                        return Integer.parseInt(args[i + 1]);
                    } catch (NumberFormatException e) {
                        // Not a number, use default
                    }
                }
                return 8080; // Default port
            }
        }
        return -1; // Not enabled
    }

    /**
     * Process a web input message through the runner directly.
     * Runs in a background thread — streams response back to web clients via WebChatServer.
     */
    private static CommandRegistry webRegistry;
    
    private static void processWebInput(com.mkpro.core.MkProContext context, String text) {
        try {
            com.mkpro.web.WebChatServer web = context.getWebChatServer();
            
            // Log
            System.out.println(ANSI_CYAN + "[Web] " + ANSI_RESET + text);

            // Handle commands (starts with /)
            if (text.startsWith("/")) {
                processWebCommand(context, text, web);
                return;
            }

            if (context.getRunner() == null || context.getCurrentSession() == null) return;

            context.getActionLogger().log("USER", text);

            // Create message
            com.google.genai.types.Content message = com.google.genai.types.Content.fromParts(
                new com.google.genai.types.Part[]{com.google.genai.types.Part.fromText(text)});

            // Get agent info for display
            com.mkpro.models.AgentConfig coordConfig = context.getAgentConfigs().get("Coordinator");
            String model = coordConfig != null ? coordConfig.getModelName() : "llama3";
            String agent = "Coordinator";

            StringBuilder responseBuilder = new StringBuilder();
            java.util.concurrent.atomic.AtomicBoolean firstChunk = new java.util.concurrent.atomic.AtomicBoolean(false);

            context.getRunner().runAsync(context.getCurrentSession().sessionKey(), message)
                .blockingSubscribe(event -> {
                    event.content().ifPresent(content -> {
                        content.parts().ifPresent(parts -> {
                            for (com.google.genai.types.Part part : parts) {
                                part.text().ifPresent(t -> {
                                    if (firstChunk.compareAndSet(false, true)) {
                                        // Check if delegation happened
                                        String delegated = com.mkpro.agents.AgentManager.lastDelegatedAgent;
                                        web.broadcastStreamStart(
                                            delegated != null ? delegated : agent, model);
                                    }
                                    responseBuilder.append(t);
                                    web.broadcastStreamChunk(t);
                                });
                            }
                        });
                    });
                }, error -> {
                    web.broadcastStreamChunk("\n\n[Error: " + error.getMessage() + "]");
                    web.broadcastStreamEnd();
                }, () -> {
                    web.broadcastStreamEnd();
                    
                    // Log response
                    if (responseBuilder.length() > 0) {
                        String delegated = com.mkpro.agents.AgentManager.lastDelegatedAgent;
                        String loggedResponse = responseBuilder.toString();
                        if (delegated != null) {
                            loggedResponse = ">> Delegating to " + delegated + "...\n" + loggedResponse;
                        }
                        context.getActionLogger().log("Coordinator", loggedResponse);
                        com.mkpro.agents.AgentManager.lastDelegatedAgent = null;
                    }
                });
        } catch (Exception e) {
            if (context.getWebChatServer() != null) {
                context.getWebChatServer().broadcastStreamChunk("\n\n[Error: " + e.getMessage() + "]");
                context.getWebChatServer().broadcastStreamEnd();
            }
        }
    }
}