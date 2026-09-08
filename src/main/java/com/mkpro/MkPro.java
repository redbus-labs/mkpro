package com.mkpro;

import com.mkpro.core.MkProContext;
import com.mkpro.core.BootstrapService;
import com.mkpro.ui.TerminalUI;
import com.mkpro.commands.CommandRegistry;
import com.mkpro.commands.impl.*;

public class MkPro {
    // ANSI Color Constants — delegated to central AnsiColors class
    public static final String ANSI_RESET = com.mkpro.ui.AnsiColors.RESET;
    public static final String ANSI_BRIGHT_GREEN = com.mkpro.ui.AnsiColors.BRIGHT_GREEN;
    public static final String ANSI_LIGHT_ORANGE = com.mkpro.ui.AnsiColors.LIGHT_ORANGE;
    public static final String ANSI_YELLOW = com.mkpro.ui.AnsiColors.YELLOW;
    public static final String ANSI_BLUE = com.mkpro.ui.AnsiColors.BLUE;
    public static final String ANSI_GREEN = com.mkpro.ui.AnsiColors.GREEN;
    public static final String ANSI_RED = com.mkpro.ui.AnsiColors.RED;
    public static final String ANSI_CYAN = com.mkpro.ui.AnsiColors.CYAN;
    public static final String ANSI_DIM = com.mkpro.ui.AnsiColors.DIM;
    public static final String ANSI_PURPLE = com.mkpro.ui.AnsiColors.PURPLE;
    public static final String ANSI_LIGHT_PURPLE = com.mkpro.ui.AnsiColors.LIGHT_PURPLE;
    public static final String ANSI_WHITE = com.mkpro.ui.AnsiColors.WHITE;
    public static final String ANSI_BRIGHT_MAGENTA = com.mkpro.ui.AnsiColors.BRIGHT_MAGENTA;
    public static final String ANSI_BOLD = com.mkpro.ui.AnsiColors.BOLD;

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
                webServer.setContext(context);
                if (context.getKnowledgeStore() != null && context.getTopicIndex() != null) {
                    webServer.setKnowledgeComponents(context.getKnowledgeStore(), context.getTopicIndex());
                }
                webServer.start();
                context.setWebChatServer(webServer);
            }

            // 2b. Initialize Event Bus and register sinks
            com.mkpro.events.MkProEventBus eventBus = new com.mkpro.events.MkProEventBus();
            com.mkpro.events.MkProEventBus.INSTANCE = eventBus;
            eventBus.register(new com.mkpro.events.TerminalSink());
            if (context.getWebChatServer() != null) {
                eventBus.register(new com.mkpro.events.WebSocketSink(context.getWebChatServer()));
            }
            context.setEventBus(eventBus);

            // Initialize EditApprovalService
            com.mkpro.events.EditApprovalService approvalService = new com.mkpro.events.EditApprovalService();
            com.mkpro.events.EditApprovalService.INSTANCE = approvalService;

            // Wire event bus to MakerLoop
            if (context.getMakerLoop() != null) {
                context.getMakerLoop().setEventBus(eventBus);
                // Set model save path for periodic saves
                java.nio.file.Path modelPath = com.mkpro.utils.PathUtils.getProjectPath().resolve(".mkpro").resolve("markov_model.dat");
                context.getMakerLoop().setModelSavePath(modelPath);
                // Wire FactEngine for pre-turn injection and post-turn validation
                if (context.getFactEngine() != null) {
                    context.getMakerLoop().setFactEngine(context.getFactEngine());
                }
                // Wire knowledge components for proactive gap detection
                if (context.getKnowledgeScheduler() != null) {
                    context.getMakerLoop().setKnowledgeComponents(
                        context.getKnowledgeScheduler(),
                        context.getKnowledgeStore(),
                        context.getTopicIndex());
                    // LLM callback: use runner to ask LLM for topic suggestions
                    final com.mkpro.core.MkProContext ctx2 = context;
                    context.getMakerLoop().setLlmCallback(prompt -> {
                        try {
                            if (ctx2.getRunner() == null || ctx2.getCurrentSession() == null) return null;
                            com.mkpro.knowledge.RequestKnowledgeTool.enterSchedulerContext();
                            try {
                                com.google.genai.types.Content msg = com.google.genai.types.Content.fromParts(
                                    new com.google.genai.types.Part[]{com.google.genai.types.Part.fromText(prompt)});
                                StringBuilder resp = new StringBuilder();
                                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                                ctx2.getRunner().runAsync(ctx2.getCurrentSession().sessionKey(), msg)
                                    .blockingSubscribe(
                                        event -> event.content().ifPresent(c -> c.parts().ifPresent(parts -> {
                                            for (com.google.genai.types.Part part : parts) {
                                                part.text().ifPresent(resp::append);
                                            }
                                        })),
                                        error -> latch.countDown(),
                                        latch::countDown
                                    );
                                latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
                                return resp.toString().trim().isEmpty() ? null : resp.toString().trim();
                            } finally {
                                com.mkpro.knowledge.RequestKnowledgeTool.exitSchedulerContext();
                            }
                        } catch (Exception e) { return null; }
                    });
                }
            }

            // 3. Initialize Command Registry
            CommandRegistry registry = new CommandRegistry();
            registerCommands(registry);

            // Wire command registry to web server for /api/command endpoint
            if (context.getWebChatServer() != null) {
                context.getWebChatServer().setCommandRegistry(registry);
            }
            
            // 4. Wire web input handler (web messages processed directly via runner in background thread)
            if (context.getWebChatServer() != null) {
                final com.mkpro.core.MkProContext ctx = context;
                context.getWebChatServer().setInputHandler(new com.mkpro.web.WebChatServer.WebInputHandler() {
                    @Override
                    public void onWebInput(String text) {
                        new Thread(() -> processWebInput(ctx, text), "web-input").start();
                    }
                    @Override
                    public void onWebInputWithImages(String text, java.util.List<com.mkpro.web.WebChatServer.ImageAttachment> images) {
                        new Thread(() -> processWebInputWithImages(ctx, text, images), "web-input").start();
                    }
                });
            }

            // 5. Restore user preferences from previous session
            restoreSessionPreferences(context, args, registry);

            // 6. Start the UI Loop
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
        registry.register(new OllamaCommand());
        registry.register(new HistoryCommand());
        registry.register(new KnowledgeCommand());
        registry.register(new WebCommand());
        registry.register(new SchedulerCommand());
        registry.register(new FactsCommand());
        registry.register(new JlamaCommand());
        registry.register(new CaptureCommand());
        registry.register(new CompactCommand());
        registry.register(new SshCommand());
        registry.register(new ExecCommand());
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
            // Capture ALL output: System.out + terminal writer
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.io.PrintStream captureStream = new java.io.PrintStream(baos, true, "UTF-8");
            java.io.PrintStream originalOut = System.out;
            
            // Redirect System.out
            System.setOut(captureStream);

            // Temporarily replace terminal with one that writes to our capture buffer
            org.jline.terminal.Terminal origTerminal = context.getTerminal();
            org.jline.terminal.Terminal captureTerminal = null;
            try {
                captureTerminal = org.jline.terminal.TerminalBuilder.builder()
                    .streams(new java.io.ByteArrayInputStream(new byte[0]), baos)
                    .dumb(true)
                    .build();
                context.setTerminal(captureTerminal);
            } catch (Exception e) {
                // If terminal creation fails, proceed with just System.out capture
            }

            try {
                if (webRegistry == null) {
                    webRegistry = new CommandRegistry();
                    registerCommands(webRegistry);
                }
                boolean handled = webRegistry.executeCommand(text, context);
                if (!handled) {
                    System.out.println("Unknown command: " + text);
                }
                // Flush the capture terminal writer
                if (captureTerminal != null) {
                    captureTerminal.writer().flush();
                }
            } finally {
                System.setOut(originalOut);
                context.setTerminal(origTerminal);
                if (captureTerminal != null) {
                    try { captureTerminal.close(); } catch (Exception ignored) {}
                }
            }
            
            String output = baos.toString("UTF-8");
            if (!output.isEmpty()) {
                // Strip ANSI codes for web display
                String clean = output.replaceAll("\u001b\\[[0-9;]*m", "");
                if (context.getEventBus() != null) {
                    context.getEventBus().emit(com.mkpro.events.MkProEvent.streamStart("System", "command"));
                    context.getEventBus().emit(com.mkpro.events.MkProEvent.streamChunk("```\n" + clean + "```"));
                    context.getEventBus().emit(com.mkpro.events.MkProEvent.streamEnd());
                }
            }
            
            // Also print to terminal
            originalOut.print(ANSI_CYAN + "[Web] " + ANSI_RESET + text + "\n");
            originalOut.print(output);
        } catch (Exception e) {
            if (context.getEventBus() != null) {
                context.getEventBus().emit(com.mkpro.events.MkProEvent.streamStart("System", "error"));
                context.getEventBus().emit(com.mkpro.events.MkProEvent.streamChunk("Error: " + e.getMessage()));
                context.getEventBus().emit(com.mkpro.events.MkProEvent.streamEnd());
            }
        }
    }

    /**
     * Restore user preferences from previous session (web, scheduler).
     * Only activates if not already started via command-line flags.
     */
    private static void restoreSessionPreferences(com.mkpro.core.MkProContext context, String[] args, CommandRegistry registry) {
        try {
            // Restore /web if user had it on and not already started via --web flag
            if (context.getWebChatServer() == null) {
                String webPref = context.getCentralMemory().getMemory("pref:web");
                if (webPref != null && !webPref.isEmpty() && !"off".equals(webPref)) {
                    try {
                        int port = Integer.parseInt(webPref);
                        System.out.println(ANSI_DIM + "  Restoring /web on port " + port + " (from previous session)" + ANSI_RESET);
                        registry.executeCommand("/web on " + port, context);
                    } catch (NumberFormatException e) {
                        // Invalid port stored, skip
                    }
                }
            }

            // Restore /scheduler if user had it on and not already started via --scheduler flag
            if (context.getKnowledgeScheduler() == null && !context.isSchedulerEnabled()) {
                String schedPref = context.getCentralMemory().getMemory("pref:scheduler");
                if ("on".equals(schedPref)) {
                    System.out.println(ANSI_DIM + "  Restoring /scheduler on (from previous session)" + ANSI_RESET);
                    registry.executeCommand("/scheduler on", context);
                }
            }
        } catch (Exception e) {
            // Silent — preference restore should never block startup
        }
    }

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
    
    /**
     * Public accessor for processWebInput — used by /web command to wire input handler.
     */
    public static void processWebInputPublic(com.mkpro.core.MkProContext context, String text) {
        processWebInput(context, text);
    }

    /**
     * Process web input that includes image attachments (multimodal).
     * Builds a Content with both text and inline image parts for vision-capable models.
     */
    private static void processWebInputWithImages(com.mkpro.core.MkProContext context, String text,
                                                   java.util.List<com.mkpro.web.WebChatServer.ImageAttachment> images) {
        try {
            if (context.getRunner() == null || context.getCurrentSession() == null) return;

            System.out.println(ANSI_CYAN + "[Web] " + ANSI_RESET + text + " [+" + images.size() + " image(s)]");

            // Build multimodal parts: text + images
            java.util.List<com.google.genai.types.Part> parts = new java.util.ArrayList<>();
            parts.add(com.google.genai.types.Part.fromText(text));

            for (com.mkpro.web.WebChatServer.ImageAttachment img : images) {
                com.google.genai.types.Part imagePart = com.google.genai.types.Part.builder()
                    .inlineData(com.google.genai.types.Blob.builder()
                        .mimeType(img.mimeType())
                        .data(img.data())
                        .build())
                    .build();
                parts.add(imagePart);
            }

            com.google.genai.types.Content message = com.google.genai.types.Content.builder()
                .role("user")
                .parts(parts)
                .build();

            // Get agent info for display
            com.mkpro.models.AgentConfig coordConfig = context.getAgentConfigs().get("Coordinator");
            String model = coordConfig != null ? coordConfig.getModelName() : "llama3";
            String agent = "Coordinator";

            StringBuilder responseBuilder = new StringBuilder();
            java.util.concurrent.atomic.AtomicBoolean firstChunk = new java.util.concurrent.atomic.AtomicBoolean(false);

            context.getRunner().runAsync(context.getCurrentSession().sessionKey(), message)
                .blockingSubscribe(event -> {
                    event.content().ifPresent(content -> {
                        content.parts().ifPresent(ps -> {
                            for (com.google.genai.types.Part part : ps) {
                                part.text().ifPresent(t -> {
                                    if (firstChunk.compareAndSet(false, true)) {
                                        String delegated = com.mkpro.agents.AgentManager.lastDelegatedAgent;
                                        if (context.getEventBus() != null) {
                                            context.getEventBus().emit(com.mkpro.events.MkProEvent.streamStart(
                                                delegated != null ? delegated : agent, model));
                                        }
                                    }
                                    responseBuilder.append(t);
                                    if (context.getEventBus() != null) {
                                        context.getEventBus().emit(com.mkpro.events.MkProEvent.streamChunk(t));
                                    }
                                });
                            }
                        });
                    });
                }, error -> {
                    if (context.getEventBus() != null) {
                        context.getEventBus().emit(com.mkpro.events.MkProEvent.streamChunk("\n\n[Error: " + error.getMessage() + "]"));
                        context.getEventBus().emit(com.mkpro.events.MkProEvent.streamEnd());
                    }
                }, () -> {
                    if (context.getEventBus() != null) {
                        context.getEventBus().emit(com.mkpro.events.MkProEvent.streamEnd());
                    }
                });
        } catch (Exception e) {
            System.err.println("[Web] Error processing image input: " + e.getMessage());
        }
    }

    /**
     * Get (or lazily create) the web command registry.
     */
    public static CommandRegistry getWebRegistry() {
        if (webRegistry == null) {
            webRegistry = new CommandRegistry();
            registerCommands(webRegistry);
        }
        return webRegistry;
    }

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

            // Log with sender identity
            String sender = context.getWebChatServer() != null ? context.getWebChatServer().getLastWebSender() : "web";
            context.getActionLogger().log("USER@" + sender, text);

            // Markov routing (same as TerminalUI)
            String line = text;
            if (context.getMarkovRouter() != null && context.getMarkovRouter().getTotalObservations() > 20) {
                com.mkpro.routing.IntentClassifier intentClassifier = new com.mkpro.routing.IntentClassifier();
                intentClassifier.setLearnedPatterns(context.getMarkovRouter().getLearnedPatterns());

                com.mkpro.routing.IntentClassifier.TaskCategory category = intentClassifier.classify(line);
                double intentConfidence = intentClassifier.classifyWithConfidence(line);

                if (category == com.mkpro.routing.IntentClassifier.TaskCategory.GENERAL) {
                    com.mkpro.routing.IntentClassifier.TaskCategory learned = intentClassifier.classifyWithLearnedPatterns(line);
                    if (learned != com.mkpro.routing.IntentClassifier.TaskCategory.GENERAL) {
                        category = learned;
                        intentConfidence = 0.5;
                    }
                }

                // YAML routing keywords
                String directAgent = intentClassifier.classifyToAgent(line);
                boolean markovRouted = false;
                if (directAgent != null) {
                    if (context.getEventBus() != null) context.getEventBus().emit(com.mkpro.events.MkProEvent.routingKeywords(directAgent));
                    line = "Delegate to " + directAgent + ": " + line;
                    markovRouted = true;
                }

                if (!markovRouted) {
                    boolean shouldTryRoute = (intentConfidence > 0.3 && category != com.mkpro.routing.IntentClassifier.TaskCategory.GENERAL);
                    if (shouldTryRoute) {
                        com.mkpro.routing.MarkovRouter.RoutingDecision decision = context.getMarkovRouter().route(category, null);
                        if (decision.shouldRoute && !"Coordinator".equals(decision.agent)) {
                            if (context.getEventBus() != null) context.getEventBus().emit(
                                com.mkpro.events.MkProEvent.routing(decision.agent, String.valueOf((int)(decision.confidence * 100)), category.name()));
                            line = "Delegate to " + decision.agent + ": " + line;
                            context.getMarkovRouter().recordTransition(category, null, decision.agent);
                        } else {
                            if (context.getEventBus() != null) context.getEventBus().emit(
                                com.mkpro.events.MkProEvent.routingBelow(decision.agent, String.valueOf((int)(decision.confidence * 100))));
                        }
                    } else {
                        if (context.getEventBus() != null) context.getEventBus().emit(
                            com.mkpro.events.MkProEvent.routingBelow("Coordinator", String.valueOf((int)(intentConfidence * 100))));
                    }
                }
            } else if (context.getMarkovRouter() != null) {
                if (context.getEventBus() != null) context.getEventBus().emit(
                    com.mkpro.events.MkProEvent.routingInactive(String.valueOf(context.getMarkovRouter().getTotalObservations())));
            }

            // Maker: track goal
            if (context.getMakerEnabled() != null && context.getMakerEnabled().get() && context.getMakerLoop() != null) {
                context.getMakerLoop().onUserInput(text);
            }

            // Create message (use routed line, not original text)
            com.google.genai.types.Content message = com.google.genai.types.Content.fromParts(
                new com.google.genai.types.Part[]{com.google.genai.types.Part.fromText(line)});

            // Get agent info for display
            com.mkpro.models.AgentConfig coordConfig = context.getAgentConfigs().get("Coordinator");
            String model = coordConfig != null ? coordConfig.getModelName() : "llama3";
            String agent = "Coordinator";

            StringBuilder responseBuilder = new StringBuilder();
            java.util.concurrent.atomic.AtomicBoolean firstChunk = new java.util.concurrent.atomic.AtomicBoolean(false);

            // Stream knowledge monitor for web path
            final com.mkpro.knowledge.StreamKnowledgeMonitor webStreamMonitor;
            if (context.getKnowledgeScheduler() != null && context.getTopicIndex() != null) {
                webStreamMonitor = new com.mkpro.knowledge.StreamKnowledgeMonitor(
                    context.getKnowledgeScheduler(), context.getTopicIndex(), null); // No LLM callback for web (avoid runner contention)
            } else {
                webStreamMonitor = null;
            }

            context.getRunner().runAsync(context.getCurrentSession().sessionKey(), message)
                .blockingSubscribe(event -> {
                    event.content().ifPresent(content -> {
                        content.parts().ifPresent(parts -> {
                            for (com.google.genai.types.Part part : parts) {
                                part.text().ifPresent(t -> {
                                    if (firstChunk.compareAndSet(false, true)) {
                                        // Check if delegation happened
                                        String delegated = com.mkpro.agents.AgentManager.lastDelegatedAgent;
                                        if (context.getEventBus() != null) {
                                            context.getEventBus().emit(com.mkpro.events.MkProEvent.streamStart(
                                                delegated != null ? delegated : agent, model));
                                        }
                                    }
                                    responseBuilder.append(t);
                                    if (webStreamMonitor != null) webStreamMonitor.onChunk(t);
                                    if (context.getEventBus() != null) {
                                        context.getEventBus().emit(com.mkpro.events.MkProEvent.streamChunk(t));
                                    }
                                });
                            }
                        });
                    });
                }, error -> {
                    if (context.getEventBus() != null) {
                        context.getEventBus().emit(com.mkpro.events.MkProEvent.streamChunk("\n\n[Error: " + error.getMessage() + "]"));
                        context.getEventBus().emit(com.mkpro.events.MkProEvent.streamEnd());
                    }
                }, () -> {
                    if (context.getEventBus() != null) {
                        context.getEventBus().emit(com.mkpro.events.MkProEvent.streamEnd());
                    }
                    
                    // Stream knowledge monitor: end
                    if (webStreamMonitor != null) webStreamMonitor.onStreamEnd();

                    // Log response
                    if (responseBuilder.length() > 0) {
                        String delegated = com.mkpro.agents.AgentManager.lastDelegatedAgent;
                        String loggedResponse = responseBuilder.toString();
                        if (delegated != null) {
                            loggedResponse = ">> Delegating to " + delegated + "...\n" + loggedResponse;
                        }
                        context.getActionLogger().log("Coordinator", loggedResponse);

                        // Maker: observe turn result + record tool usage
                        if (context.getMakerEnabled() != null && context.getMakerEnabled().get() && context.getMakerLoop() != null) {
                            String agentUsed = delegated != null ? delegated : "Coordinator";
                            String response = responseBuilder.toString();
                            java.util.List<String> toolsDetected = new java.util.ArrayList<>();
                            if (response.contains("[Shell]") || response.contains("run_shell")) toolsDetected.add("shell");
                            if (response.contains("file_write") || response.contains("Saved") || response.contains("saved")) toolsDetected.add("file_write");
                            if (response.contains("file_read") || response.contains("[VectorSearch]")) toolsDetected.add("file_read");
                            if (response.contains("[FetchURL]")) toolsDetected.add("fetch_url");
                            if (response.contains("[Memory]")) toolsDetected.add("central_memory");
                            if (response.contains("[Index]")) toolsDetected.add("index_codebase");

                            boolean success = !response.contains("Error executing") && !response.contains("FAILED");
                            context.getMakerLoop().onTurnComplete(agentUsed, toolsDetected, success, response);

                            // Layer 2: record agent→tool transitions
                            if (!toolsDetected.isEmpty() && context.getMarkovRouter() != null) {
                                com.mkpro.routing.IntentClassifier.TaskCategory cat = context.getMakerLoop().getCurrentGoal() != null
                                    ? context.getMakerLoop().getCurrentGoal().getCategory()
                                    : com.mkpro.routing.IntentClassifier.TaskCategory.GENERAL;
                                context.getMarkovRouter().recordToolUsage(agentUsed, cat, toolsDetected);
                            }
                        }

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
