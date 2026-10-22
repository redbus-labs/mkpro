package com.mkpro.ui;

import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.mkpro.commands.CommandRegistry;
import com.mkpro.core.MkProContext;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

public class TerminalUI {

    private static boolean isAutoContinue = false;

    public static void setAutoContinue(boolean value) {
        isAutoContinue = value;
    }

    public static void start(MkProContext context, CommandRegistry registry) {
        try {
            Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .build();

            LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();

            context.setTerminal(terminal);
            context.setLineReader(reader);

            System.out.println("╔══════════════════════════════════════════════════════════════╗");
            System.out.println("║                MkPro - Agentic CLI v1.0                      ║");
            System.out.println("║      Type your request, or /help for available commands.     ║");
            System.out.println("╚══════════════════════════════════════════════════════════════╝");

            while (true) {
                String line;
                try {
                    String timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                    Path cwdPath = Paths.get(".").toAbsolutePath().normalize();
                    String cwd;
                    if (cwdPath.getNameCount() <= 3) {
                        cwd = cwdPath.toString();
                    } else {
                        // Show last 2 segments: .../parent/current
                        cwd = ".../" + cwdPath.getName(cwdPath.getNameCount() - 2) + "/" + cwdPath.getFileName();
                    }
                    String prompt = "\n[" + timestamp + "] " + cwd + "> ";
                    line = reader.readLine(prompt);
                } catch (UserInterruptException e) {
                    // Ctrl+C
                    continue;
                } catch (EndOfFileException e) {
                    // Ctrl+D
                    break;
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

                        // Fast-route evaluation prior to Coordinator turn
                        com.mkpro.routing.FastRouteEvaluator.EvaluationResult eval = 
                            com.mkpro.routing.FastRouteEvaluator.evaluate(line, context);
                        
                        if (eval.shouldDirectDispatch && eval.targetAgent != null) {
                            String targetAgent = eval.targetAgent;
                            if (context.getEventBus() != null) {
                                if ("direct_keyword_match".equals(eval.reason)) {
                                    context.getEventBus().emit(com.mkpro.events.MkProEvent.routingKeywords(targetAgent));
                                } else if (eval.category != null) {
                                    context.getEventBus().emit(com.mkpro.events.MkProEvent.routing(targetAgent, String.valueOf((int)(eval.confidence * 100)), eval.category.name()));
                                }
                            }
                            if (context.getMarkovRouter() != null && eval.category != null) {
                                context.getMarkovRouter().recordTransition(eval.category, null, targetAgent);
                            }
                            String guidance = eval.formatRoutingGuidance();
                            if (guidance != null) {
                                line = line + "\n\n" + guidance;
                            }
                        } else {
                            // Normal Markov / intent check when routing guidance is not triggered
                            if (!isAutoContinue && context.getMarkovRouter() != null && context.getMarkovRouter().getTotalObservations() > 20) {
                                com.mkpro.routing.IntentClassifier intentClassifier = new com.mkpro.routing.IntentClassifier();
                                intentClassifier.setLearnedPatterns(context.getMarkovRouter().getLearnedPatterns());
                                com.mkpro.routing.IntentClassifier.TaskCategory category = intentClassifier.classify(line);
                                double intentConfidence = intentClassifier.classifyWithConfidence(line);
                                if (context.getEventBus() != null) {
                                    context.getEventBus().emit(com.mkpro.events.MkProEvent.routingBelow("Coordinator", String.valueOf((int)(intentConfidence * 100))));
                                }
                            } else if (!isAutoContinue && context.getMarkovRouter() != null) {
                                if (context.getEventBus() != null) {
                                    context.getEventBus().emit(com.mkpro.events.MkProEvent.routingInactive(String.valueOf(context.getMarkovRouter().getTotalObservations())));
                                }
                            }
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

                        if (context.getEventBus() != null) {
                            context.getEventBus().emit(com.mkpro.events.MkProEvent.streamStart(displayAgent, displayModel));
                        }

                        StringBuilder fullResponse = new StringBuilder();
                        try {
                            context.getRunner().runAsync(context.getCurrentSession().sessionKey(), message)
                                .blockingSubscribe(event -> {
                                    event.content().ifPresent(content -> {
                                        content.parts().ifPresent(ps -> {
                                            for (com.google.genai.types.Part part : ps) {
                                                part.text().ifPresent(text -> {
                                                    if (!text.isEmpty()) {
                                                        if (isThinking.get()) {
                                                            if (text.trim().startsWith("<think>")) {
                                                                return;
                                                            }
                                                            if (text.contains("</think>")) {
                                                                isThinking.set(false);
                                                                int idx = text.indexOf("</think>");
                                                                text = text.substring(idx + 8);
                                                                if (text.trim().isEmpty()) {
                                                                    return;
                                                                }
                                                            } else if (text.trim().startsWith("/")) {
                                                                // might be thinking block continuation
                                                            }
                                                        }

                                                        if (!firstChunkReceived.get()) {
                                                            firstChunkReceived.set(true);
                                                        }
                                                        fullResponse.append(text);
                                                        System.out.print(text);
                                                        if (context.getEventBus() != null) {
                                                            context.getEventBus().emit(com.mkpro.events.MkProEvent.streamChunk(text));
                                                        }
                                                    }
                                                });
                                            }
                                        });
                                    });
                                }, error -> {
                                    System.err.println("\nError executing turn: " + error.getMessage());
                                    if (context.getEventBus() != null) {
                                        context.getEventBus().emit(com.mkpro.events.MkProEvent.streamChunk("\n[Error: " + error.getMessage() + "]"));
                                    }
                                });

                        } catch (Exception e) {
                            System.err.println("\nError executing turn: " + e.getMessage());
                            if (context.getEventBus() != null) {
                                context.getEventBus().emit(com.mkpro.events.MkProEvent.streamChunk("\n[Error: " + e.getMessage() + "]"));
                            }
                        }

                        if (context.getEventBus() != null) {
                            context.getEventBus().emit(com.mkpro.events.MkProEvent.streamEnd());
                        }
                        System.out.println();

                        // Log assistant response for training data export
                        context.getActionLogger().log("ASSISTANT", fullResponse.toString());

                        // Maker: record turn completion
                        if (context.getMakerEnabled().get() && context.getMakerLoop() != null) {
                            context.getMakerLoop().onTurnComplete("Coordinator", java.util.Collections.emptyList(), false, fullResponse.toString());
                        }
                    }
                }
            }

        } catch (IOException e) {
            System.err.println("Terminal UI error: " + e.getMessage());
        }
    }
}
