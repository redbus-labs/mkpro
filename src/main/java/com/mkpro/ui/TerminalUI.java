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
        System.out.println(ANSI_CYAN + "Welcome to MkPro! Type 'help' for commands." + ANSI_RESET);

        while (true) {
            String line = null;
            try {
                if (context.getMakerEnabled().get()) {
                    handleMakerLoop();
                    continue;
                }

                String timestamp = LocalTime.now().format(PROMPT_TIME_FORMATTER);
                String prompt = "\n[" + timestamp + "] mkpro> ";
                line = context.getLineReader().readLine(prompt);

                if (line == null) break;
                line = line.trim();
                if (line.isEmpty()) continue;

                if (registry.executeCommand(line, context)) {
                    // Command was handled
                } else {
                    // Fallback to Runner
                    if (context.getRunner() != null && context.getCurrentSession() != null) {
                        com.google.genai.types.Content message = com.google.genai.types.Content.fromParts(
                            new com.google.genai.types.Part[]{com.google.genai.types.Part.fromText(line)}
                        );
                        context.getRunner().runAsync(context.getCurrentSession().sessionKey(), message)
                            .blockingSubscribe(event -> {
                                event.content().ifPresent(content -> {
                                    content.parts().ifPresent(parts -> {
                                        for (com.google.genai.types.Part part : parts) {
                                            part.text().ifPresent(System.out::print);
                                        }
                                    });
                                });
                                if (Boolean.FALSE.equals(event.partial().orElse(null))) {
                                    System.out.println();
                                }
                            }, error -> {
                                System.err.println("\n[Error] " + error.getMessage());
                            });
                    } else {
                        System.out.println(ANSI_YELLOW + "No runner or session initialized. Input ignored." + ANSI_RESET);
                    }
                }

            } catch (UserInterruptException e) {
                // Ignore
            } catch (EndOfFileException e) {
                break;
            } catch (Exception e) {
                System.err.println(ANSI_RED + "Error: " + e.getMessage() + ANSI_RESET);
                if (context.isVerbose()) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void handleMakerLoop() {
        System.out.println(ANSI_PURPLE + "[Maker] Logic active..." + ANSI_RESET);
        context.getMakerEnabled().set(false);
    }
}
