package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.commands.CommandRegistry;
import com.mkpro.core.MkProContext;

public class HelpCommand implements Command {
    private CommandRegistry registry;

    public HelpCommand() {
    }

    public HelpCommand(CommandRegistry registry) {
        this.registry = registry;
    }

    public void setRegistry(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void execute(String[] args, MkProContext context) {
        if (registry == null) {
            context.getTerminal().writer().println("Error: Command registry not initialized in HelpCommand.");
            return;
        }
        
        context.getTerminal().writer().println("\nAvailable Commands:");
        registry.getCommands().values().stream()
                .distinct() // Ensure aliases aren't listed twice
                .sorted((a, b) -> a.getName().compareTo(b.getName()))
                .forEach(cmd -> {
                    context.getTerminal().writer().printf("  /%-10s - %s\n", cmd.getName(), cmd.getDescription());
                });
        context.getTerminal().writer().println();
        context.getTerminal().flush();
    }

    @Override
    public String getName() {
        return "help";
    }

    @Override
    public String getDescription() {
        return "Show available commands";
    }
}
