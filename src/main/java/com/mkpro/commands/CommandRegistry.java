package com.mkpro.commands;

import com.mkpro.core.MkProContext;
import java.util.HashMap;
import java.util.Map;

public class CommandRegistry {
    private final Map<String, Command> commands = new HashMap<>();

    public void register(Command command) {
        String name = command.getName();
        commands.put(name, command);
        // Automatically register the /prefix version as an alias
        if (!name.startsWith("/")) {
            commands.put("/" + name, command);
        }
    }

    public boolean executeCommand(String input, MkProContext context) {
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return false;

        String[] parts = trimmed.split("\\s+");
        String commandName = parts[0];
        
        // Check for direct match (handles both "cmd" and "/cmd" if registered)
        Command command = commands.get(commandName);
        
        // If not found, try adding/removing slash as a fallback
        if (command == null) {
            if (commandName.startsWith("/")) {
                command = commands.get(commandName.substring(1));
            } else {
                command = commands.get("/" + commandName);
            }
        }

        if (command != null) {
            try {
                String[] args = new String[parts.length - 1];
                System.arraycopy(parts, 1, args, 0, args.length);
                command.execute(args, context);
                return true;
            } catch (Exception e) {
                context.getTerminal().writer().println("Error executing command: " + e.getMessage());
                if (context.isVerbose()) {
                    e.printStackTrace(context.getTerminal().writer());
                }
                return true; // Still handled as a command
            }
        } else {
            // If it started with a slash but wasn't found, it's an error
            if (commandName.startsWith("/")) {
                context.getTerminal().writer().println("Unknown command: " + commandName + ". Type /help for assistance.");
                return true;
            }
            // Otherwise, let it fall through to the runner
            return false;
        }
    }

    public Map<String, Command> getCommands() {
        return commands;
    }
}
