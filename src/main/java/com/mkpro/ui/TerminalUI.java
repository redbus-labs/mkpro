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

                        AtomicBoolean isThinking = new AtomicBoolean(true);
                        AtomicBoolean firstChunkReceived = new AtomicBoolean(false);

                        // Start spinner daemon thread
                        Thread spinnerThread = new Thread(() -> {
                            String[] syms = {"|", "/", "-", "\\"};
                            int spinnerIdx = 0;
                            try {
                                org.jline.terminal.Terminal terminal = context.getLineReader().getTerminal();
                                while (isThinking.get() && !firstChunkReceived.get()) {
                                    terminal.writer().print("\r" + ANSI_BLUE + "Thinking " + syms[spinnerIdx++ % syms.length] + " " + ANSI_RESET);
                                    terminal.writer().flush();
                                    Thread.sleep(100);
                                }
                                // Clear the spinner line
                                terminal.writer().print("\r" + " ".repeat(20) + "\r");
                                terminal.writer().flush();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } catch (Exception ignored) {
                                // Fallback to System.out if JLine terminal writer is not available
                                while (isThinking.get() && !firstChunkReceived.get()) {
                                    System.out.print("\r" + ANSI_BLUE + "Thinking " + syms[spinnerIdx++ % syms.length] + " " + ANSI_RESET);
                                    System.out.flush();
                                    try {
                                        Thread.sleep(100);
                                    } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                        break;
                                    }
                                }
                                System.out.print("\r" + " ".repeat(20) + "\r");
                                System.out.flush();
                            }
                        });
                        spinnerThread.setDaemon(true);
                        spinnerThread.start();

                        try {
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
                                                    }
                                                    System.out.print(text);
                                                    System.out.flush();
                                                });
                                            }
                                        });
                                    });
                                    if (Boolean.FALSE.equals(event.partial().orElse(null))) {
                                        System.out.print(ANSI_RESET);
                                        System.out.println();
                                    }
                                }, error -> {
                                    isThinking.set(false);
                                    spinnerThread.interrupt();
                                    System.err.println(ANSI_RESET + "\n[Error] " + error.getMessage());
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
            "                          ██╗██████╗                                  ",
            "    ██████╗ ██████╗  ███████║██╔══██╗██╗   ██╗███████╗                ",
            "    ██╔══██╗██╔═══██╗██╔══██║██████╔╝██║   ██║██╔════╝                ",
            "    ██║  ╚═╝███████╔╝██║  ██║██╔══██╗██║   ██║╚██████╗                ",
            "    ██║     ██╔════╝ ╚██████║██████╔╝╚██████╔╝╚════██║                ",
            "    ╚═╝     ╚██████╗  ╚═════╝╚═════╝  ╚═════╝ ███████╔╝               "
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
