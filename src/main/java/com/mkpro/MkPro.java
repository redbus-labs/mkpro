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
    public static final String ANSI_PURPLE = "\u001b[35m";
    public static final String ANSI_LIGHT_PURPLE = "\u001b[38;5;177m";
    public static final String ANSI_WHITE = "\u001b[37m";
    public static final String ANSI_BRIGHT_MAGENTA = "\u001B[95m";
    public static final String ANSI_BOLD = "\u001b[1m";

    public static void main(String[] args) {
        try {
            // 1. Bootstrap the application context
            BootstrapService bootstrapService = new BootstrapService();
            MkProContext context = bootstrapService.bootstrap(args);

            // 2. Initialize Command Registry
            CommandRegistry registry = new CommandRegistry();
            registerCommands(registry);
            
            // 3. Start the UI Loop
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
        registry.register(new VisualizeCommand());
        registry.register(new NetworkCommand());
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
}
