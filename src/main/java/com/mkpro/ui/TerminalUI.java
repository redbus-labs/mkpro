package com.mkpro.ui;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.mkpro.core.MkProContext;
import com.mkpro.commands.CommandRegistry;
import org.jline.reader.UserInterruptException;
import org.jline.reader.EndOfFileException;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.mkpro.MkPro.*;

public class TerminalUI {
    private final MkProContext context;
    private final CommandRegistry registry;

    private static final DateTimeFormatter PROMPT_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    public TerminalUI(MkProContext context, CommandRegistry registry) {
        this.context = context;
        this.registry = registry;
    }

    public void start() {
        printBanner();

        String[] autoContHolder = {null}; // Set by Maker to auto-continue without user input

        while (true) {
            String line = null;
            boolean isAutoContinue = false;
            try {
                // Auto-continue: if Maker set a continuation message, use it instead of prompting
                if (autoContHolder[0] != null) {
                    line = autoContHolder[0];
                    autoContHolder[0] = null;
                    isAutoContinue = true;
                    System.out.println(ANSI_PURPLE + "  [Maker] Auto-continuing..." + ANSI_RESET);
                } else {
                    String timestamp = LocalTime.now().format(PROMPT_TIME_FORMATTER);
                    java.nio.file.Path cwdPath = java.nio.file.Paths.get("").toAbsolutePath();
                    String cwd;
                    if (cwdPath.getNameCount() <= 3) {
                        cwd = cwdPath.toString();
                    } else {
                        // Show last 2 segments: .../parent/current
                        cwd = ".../" + cwdPath.getName(cwdPath.getNameCount() - 2) + "/" + cwdPath.getFileName();
                    }
                    String prompt = "\n[" + timestamp + "] " + cwd + "> ";
                    line = context.getLineReader().readLine(prompt);
                }

                if (line == null) break;
                line = line.trim();
                if (line.isEmpty()) continue;

                if (registry.executeCommand(line, context)) {
                    // Command was handled
                } else {
                    // Fallback to Runner
                    if (context.getRunner() != null && context.getCurrentSession() != null) {
                        // Log user input for training data export
                        context.getActionLogger().log("USER", line);

                        // Check Markov Router — can we fast-route without LLM?
                        boolean markovRouted = false;
                        if (!isAutoContinue && context.getMarkovRouter() != null && context.getMarkovRouter().getTotalObservations() > 20) {
                            com.mkpro.routing.IntentClassifier intentClassifier = new com.mkpro.routing.IntentClassifier();
                            // Inject learned patterns from router
                            intentClassifier.setLearnedPatterns(context.getMarkovRouter().getLearnedPatterns());
                            
                            com.mkpro.routing.IntentClassifier.TaskCategory category = intentClassifier.classify(line);
                            double intentConfidence = intentClassifier.classifyWithConfidence(line);
                            
                            // If static returned GENERAL, try learned patterns
                            if (category == com.mkpro.routing.IntentClassifier.TaskCategory.GENERAL) {
                                com.mkpro.routing.IntentClassifier.TaskCategory learned = intentClassifier.classifyWithLearnedPatterns(line);
                                if (learned != com.mkpro.routing.IntentClassifier.TaskCategory.GENERAL) {
                                    category = learned;
                                    intentConfidence = 0.5; // Moderate confidence for learned matches
                                }
                            }
                            
                            // Route if: specific category detected
                            boolean shouldTryRoute = (intentConfidence > 0.3 && category != com.mkpro.routing.IntentClassifier.TaskCategory.GENERAL);
                            
                            if (shouldTryRoute) {
                                com.mkpro.routing.MarkovRouter.RoutingDecision decision = 
                                    context.getMarkovRouter().route(category, null);
                                
                                if (decision.shouldRoute && !"Coordinator".equals(decision.agent)) {
                                    System.out.println("\u001b[36m[Fast-route → " + decision.agent + 
                                        " (" + (int)(decision.confidence * 100) + "% confidence, category: " + category + ")]\u001b[0m");
                                    line = "Delegate to " + decision.agent + ": " + line;
                                    markovRouted = true;
                                    context.getMarkovRouter().recordTransition(category, null, decision.agent);
                                } else {
                                    System.out.println("\u001b[90m[Markov: " + decision.agent + 
                                        " " + (int)(decision.confidence * 100) + "% — below threshold, using Coordinator]\u001b[0m");
                                }
                            } else {
                                System.out.println("\u001b[90m[Markov: category=" + category + 
                                    ", intent=" + (int)(intentConfidence * 100) + "% — not routable, using Coordinator]\u001b[0m");
                            }
                        } else if (!isAutoContinue && context.getMarkovRouter() != null) {
                            System.out.println("\u001b[90m[Markov: inactive (" + context.getMarkovRouter().getTotalObservations() + " obs, need 20+). Run /train]\u001b[0m");
                        }

                        // Maker: track goal and inject stimulus
                        if (!isAutoContinue && context.getMakerEnabled().get() && context.getMakerLoop() != null) {
                            context.getMakerLoop().onUserInput(line);
                            String stimulus = context.getMakerLoop().generatePreTurnStimulus();
                            if (stimulus != null) {
                                line = line + "\n\n" + stimulus;
                            }
                            // If retrying, inject retry guidance
                            String retryStimulus = context.getMakerLoop().generateRetryStimulus();
                            if (retryStimulus != null) {
                                line = line + "\n\n" + retryStimulus;
                            }
                        }

                        com.google.genai.types.Content message = com.google.genai.types.Content.fromParts(
                            new com.google.genai.types.Part[]{com.google.genai.types.Part.fromText(line)}
                        );

                        AtomicBoolean isThinking = new AtomicBoolean(true);
                        AtomicBoolean firstChunkReceived = new AtomicBoolean(false);

                        // Resolve display info — show which agent is processing
                        com.mkpro.models.AgentConfig displayConfig = context.getAgentConfigs().get("Coordinator");
                        String displayAgent = "Coordinator";
                        String displayModel = displayConfig != null ? displayConfig.getModelName() : "llama3";
                        String displayProvider = displayConfig != null ? displayConfig.getProvider().name() : "OLLAMA";
                        
                        if (markovRouted) {
                            // Extract agent name from the rewritten line "Delegate to AgentName: ..."
                            String routedLine = line;
                            if (routedLine.startsWith("Delegate to ")) {
                                int colonIdx = routedLine.indexOf(':');
                                if (colonIdx > 12) {
                                    displayAgent = routedLine.substring(12, colonIdx);
                                    // Try to get the routed agent's model
                                    com.mkpro.models.AgentConfig routedConfig = context.getAgentConfigs().get(displayAgent);
                                    if (routedConfig != null) {
                                        displayModel = routedConfig.getModelName();
                                        displayProvider = routedConfig.getProvider().name();
                                    }
                                }
                            }
                        }
                        String thinkingLabel = displayAgent + "[" + displayModel + "@" + displayProvider + "] ";
                        // Capture for lambda access
                        final String finalDisplayAgent = displayAgent;
                        final String finalDisplayModel = displayModel;

                        // Start spinner daemon thread with color transitions
                        Thread spinnerThread = new Thread(() -> {
                            String[] syms = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
                            // Smooth color transition: blue → orange → white → blue...
                            // Using 256-color ANSI: blue(33), orange shades(214,208,202), white(255)
                            String[] colors = {
                                "\u001b[38;5;33m",  // blue
                                "\u001b[38;5;39m",  // light blue
                                "\u001b[38;5;45m",  // cyan-blue
                                "\u001b[38;5;81m",  // sky
                                "\u001b[38;5;117m", // pale blue
                                "\u001b[38;5;255m", // white
                                "\u001b[38;5;223m", // pale orange
                                "\u001b[38;5;216m", // light orange
                                "\u001b[38;5;214m", // orange
                                "\u001b[38;5;208m", // deep orange
                                "\u001b[38;5;202m", // red-orange
                                "\u001b[38;5;214m", // orange
                                "\u001b[38;5;223m", // pale orange
                                "\u001b[38;5;255m", // white
                                "\u001b[38;5;153m", // ice blue
                                "\u001b[38;5;111m", // medium blue
                            };
                            int spinnerIdx = 0;
                            int colorIdx = 0;
                            try {
                                org.jline.terminal.Terminal terminal = context.getLineReader().getTerminal();
                                while (isThinking.get() && !firstChunkReceived.get()) {
                                    String color = colors[colorIdx % colors.length];
                                    String sym = syms[spinnerIdx % syms.length];
                                    terminal.writer().print("\r" + color + sym + " " + thinkingLabel + ANSI_RESET);
                                    terminal.writer().flush();
                                    spinnerIdx++;
                                    colorIdx++;
                                    Thread.sleep(80);
                                }
                                // Clear the spinner line
                                terminal.writer().print("\r" + " ".repeat(thinkingLabel.length() + 4) + "\r");
                                terminal.writer().flush();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } catch (Exception ignored) {
                                // Fallback to System.out
                                while (isThinking.get() && !firstChunkReceived.get()) {
                                    String color = colors[colorIdx % colors.length];
                                    String sym = syms[spinnerIdx % syms.length];
                                    System.out.print("\r" + color + sym + " " + thinkingLabel + ANSI_RESET);
                                    System.out.flush();
                                    spinnerIdx++;
                                    colorIdx++;
                                    try { Thread.sleep(80); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                                }
                                System.out.print("\r" + " ".repeat(thinkingLabel.length() + 4) + "\r");
                                System.out.flush();
                            }
                        });
                        spinnerThread.setDaemon(true);
                        spinnerThread.start();

                        try {
                            final String finalLine = line;
                            long[] tokens = {0, 0, 0};
                            long startTime = System.currentTimeMillis();
                            StringBuilder responseBuilder = new StringBuilder();

                            context.getRunner().runAsync(context.getCurrentSession().sessionKey(), message)
                                .blockingSubscribe(event -> {
                                    // Stop spinner upon receiving first response chunk
                                    isThinking.set(false);
                                    spinnerThread.interrupt();

                                    event.content().ifPresent(content -> {
                                        content.parts().ifPresent(parts -> {
                                            for (com.google.genai.types.Part part : parts) {
                                                part.text().ifPresent(text -> {
                                                    if (firstChunkReceived.compareAndSet(false, true)) {
                                                        // First text chunk, clear spinner completely and set response color
                                                        try {
                                                            org.jline.terminal.Terminal terminal = context.getLineReader().getTerminal();
                                                            terminal.writer().print("\r" + " ".repeat(20) + "\r");
                                                            terminal.writer().flush();
                                                        } catch (Exception ignored) {
                                                            System.out.print("\r" + " ".repeat(20) + "\r");
                                                            System.out.flush();
                                                        }
                                                        System.out.print(ANSI_LIGHT_ORANGE);
                                                        // Web: stream start
                                                        if (context.getWebChatServer() != null) {
                                                            context.getWebChatServer().broadcastStreamStart(finalDisplayAgent, finalDisplayModel);
                                                        }
                                                    }
                                                    responseBuilder.append(text);
                                                    System.out.print(text);
                                                    System.out.flush();
                                                    // Web: stream chunk
                                                    if (context.getWebChatServer() != null) {
                                                        context.getWebChatServer().broadcastStreamChunk(text);
                                                    }
                                                });
                                            }
                                        });
                                    });

                                    // Capture tokens
                                    event.usageMetadata().ifPresent(u -> {
                                        tokens[0] = u.promptTokenCount().orElse(0);
                                        tokens[1] = u.candidatesTokenCount().orElse(0);
                                        tokens[2] = u.totalTokenCount().orElse(0);
                                    });

                                    if (Boolean.FALSE.equals(event.partial().orElse(null))) {
                                        System.out.print(ANSI_RESET);
                                        System.out.println();
                                    }
                                }, error -> {
                                    isThinking.set(false);
                                    spinnerThread.interrupt();
                                    System.err.println(ANSI_RESET + "\n[Error] " + error.getMessage());
                                }, () -> {
                                    // ON COMPLETE - Calculate stats
                                    long duration = System.currentTimeMillis() - startTime;
                                    
                                    // Web: stream end
                                    if (context.getWebChatServer() != null) {
                                        context.getWebChatServer().broadcastStreamEnd();
                                    }
                                    
                                    String sessId = context.getCurrentSession() != null ? context.getCurrentSession().id() : "default-session";
                                    
                                    com.mkpro.models.AgentConfig coordConfig = context.getAgentConfigs().get("Coordinator");
                                    String providerStr = coordConfig != null ? coordConfig.getProvider().name() : "OLLAMA";
                                    String modelStr = coordConfig != null ? coordConfig.getModelName() : "llama3";
                                    
                                    com.mkpro.models.AgentStat stat = new com.mkpro.models.AgentStat(
                                        "Coordinator", providerStr, modelStr, duration, true, 
                                        finalLine.length(), responseBuilder.length(), 
                                        tokens[0], tokens[1], tokens[2], sessId
                                    );
                                    context.getCentralMemory().saveAgentStat(stat);

                                    // Log Coordinator response for training data export
                                    if (responseBuilder.length() > 0) {
                                        String loggedResponse = responseBuilder.toString();
                                        // Prepend delegation info for training data extraction
                                        String delegatedTo = com.mkpro.agents.AgentManager.lastDelegatedAgent;
                                        if (delegatedTo != null) {
                                            loggedResponse = ">> Delegating to " + delegatedTo + "...\n" + loggedResponse;
                                        }
                                        context.getActionLogger().log("Coordinator", loggedResponse);
                                    }

                                    // Maker: observe turn result
                                    if (context.getMakerEnabled().get() && context.getMakerLoop() != null) {
                                        // Use tracked delegation info from AgentManager
                                        String agentUsed = com.mkpro.agents.AgentManager.lastDelegatedAgent;
                                        if (agentUsed == null) agentUsed = "Coordinator";
                                        
                                        // Detect tools from response markers
                                        String response = responseBuilder.toString();
                                        java.util.List<String> toolsDetected = new java.util.ArrayList<>();
                                        if (response.contains("[Shell]") || response.contains("run_shell")) toolsDetected.add("shell");
                                        if (response.contains("file_write") || response.contains("Saved") || response.contains("saved")) toolsDetected.add("file_write");
                                        if (response.contains("file_read") || response.contains("[VectorSearch]")) toolsDetected.add("file_read");
                                        if (response.contains("[FetchURL]")) toolsDetected.add("fetch_url");
                                        if (response.contains("[Memory]")) toolsDetected.add("central_memory");
                                        if (response.contains("[Index]")) toolsDetected.add("index_codebase");

                                        boolean success = !response.contains("Error executing") && !response.contains("FAILED");
                                        var makerAction = context.getMakerLoop().onTurnComplete(agentUsed, toolsDetected, success, response);
                                        
                                        // Reset for next turn
                                        com.mkpro.agents.AgentManager.lastDelegatedAgent = null;
                                        
                                        // Auto-continue: if Maker says CONTINUE or ESCALATE (wrap-up),
                                        // inject a continuation message for the next iteration
                                        if ((makerAction == com.mkpro.routing.MarkovRouter.MakerAction.CONTINUE
                                            || makerAction == com.mkpro.routing.MarkovRouter.MakerAction.ESCALATE)
                                            && context.getMakerLoop().getCurrentGoal() != null
                                            && context.getMakerLoop().getCurrentGoal().getTurnCount() >= 1) {
                                            autoContHolder[0] = context.getMakerLoop().generateAutoContMessage();
                                        }
                                    }
                                });
                        } finally {
                            isThinking.set(false);
                            spinnerThread.interrupt();
                            System.out.print(ANSI_RESET);
                            System.out.flush();
                        }
                    } else {
                        System.out.println(ANSI_YELLOW + "No runner or session initialized. Input ignored." + ANSI_RESET);
                    }
                }

            } catch (UserInterruptException e) {
                System.out.print(ANSI_RESET);
                System.out.flush();
            } catch (EndOfFileException e) {
                System.out.print(ANSI_RESET);
                System.out.flush();
                break;
            } catch (Exception e) {
                System.err.println(ANSI_RED + "Error: " + e.getMessage() + ANSI_RESET);
                if (context.isVerbose()) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void printBanner() {
        String username = System.getProperty("user.name");
        String date = java.time.format.DateTimeFormatter.ofPattern("EEE MMM dd, yyyy").format(java.time.LocalDate.now());

        String[] logoLines = {
            "  ███╗   ███╗██╗  ██╗██████╗ ██████╗  ██████╗ ",
            "  ████╗ ████║██║ ██╔╝██╔══██╗██╔══██╗██╔═══██╗",
            "  ██╔████╔██║█████╔╝ ██████╔╝██████╔╝██║   ██║",
            "  ██║╚██╔╝██║██╔═██╗ ██╔═══╝ ██╔══██╗██║   ██║",
            "  ██║ ╚═╝ ██║██║  ██╗██║     ██║  ██║██║   ██║",
            "  ██║     ██║██║  ██╗██║     ██║  ██║╚██████╔╝",
            "  ╚═╝     ╚═╝╚═╝  ╚═╝╚═╝     ╚═╝  ╚═╝ ╚═════╝ ",
            "                                              "
        };

        System.out.println("");
        
        // Find the maximum length of the logo lines
        int maxLength = 0;
        for (String line : logoLines) {
            if (line.length() > maxLength) {
                maxLength = line.length();
            }
        }

        // Horizontal reveal animation for the logo
        for (int col = 1; col <= maxLength; col++) {
            // Move cursor up by the number of lines to overwrite them
            if (col > 1) {
                System.out.print("\033[" + logoLines.length + "A");
            }
            
            for (String line : logoLines) {
                int endIdx = Math.min(col, line.length());
                String visiblePart = line.substring(0, endIdx);
                // Print the visible part with color, then clear to the end of the line
                System.out.println(ANSI_LIGHT_PURPLE + ANSI_BOLD + visiblePart + ANSI_RESET + "\033[K");
            }
            
            try {
                Thread.sleep(15); // 15ms per column for a fast horizontal sweep
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        System.out.println();
        
        // Symmetrical REDBUS ASCII block with bright white background (107) and bold bright red text (1;91)
                String[] redBusLines = {
            "                       ██╗██████╗                      ",
            " ██████╗ ██████╗  ███████║██╔══██╗██╗   ██╗███████╗    ",
            " ██╔══██╗██╔═══██╗██╔══██║██████╔╝██║   ██║██╔════╝    ",
            " ██║  ╚═╝███████╔╝██║  ██║██╔══██╗██║   ██║╚██████╗    ",
            " ██║     ██╔════╝ ╚██████║██████╔╝╚██████╔╝╚════██║    ",
            " ╚═╝     ╚██████╗  ╚═════╝╚═════╝  ╚═════╝ ███████╔╝   "
        };
        
        String ANSI_RED_ON_WHITE = "\u001b[1;91;107m";
        for (String line : redBusLines) {
            System.out.println(ANSI_RED_ON_WHITE + line + ANSI_RESET);
        }

        System.out.println(ANSI_WHITE + "  Built by " + ANSI_RED + ANSI_BOLD + "redBus" + ANSI_RESET + ANSI_WHITE + " Engineering " + ANSI_RESET);
        System.out.println();

        // Animate the info text typing effect horizontally (character by character)
        String[] infoLines = {
            ANSI_WHITE + "  Logged in as: " + ANSI_CYAN + username + ANSI_RESET,
            ANSI_WHITE + "  Today's Date: " + ANSI_LIGHT_PURPLE + date + ANSI_RESET,
            "",
            ANSI_WHITE + "  Tips for getting started:" + ANSI_RESET,
            ANSI_WHITE + "  1. Ask questions, edit files, or run commands." + ANSI_RESET,
            ANSI_WHITE + "  2. Be specific for the best results." + ANSI_RESET,
            ANSI_WHITE + "  3. " + ANSI_CYAN + "/help" + ANSI_WHITE + " for more information." + ANSI_RESET
        };

        for (String line : infoLines) {
            if (line.isEmpty()) {
                System.out.println();
                continue;
            }
            
            boolean inAnsi = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                System.out.print(c);
                
                if (c == '\033') {
                    inAnsi = true;
                }
                if (inAnsi && c == 'm') {
                    inAnsi = false;
                }
                
                if (!inAnsi) {
                    System.out.flush();
                    try {
                        Thread.sleep(5); // 5ms per character
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            System.out.println();
        }
        System.out.println("");
    }

    private void handleMakerLoop() {
        System.out.println(ANSI_PURPLE + "[Maker] Logic active..." + ANSI_RESET);
        context.getMakerEnabled().set(false);
    }
}