package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.models.McpServer;

import java.util.List;

public class McpCommand implements Command {
    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        List<McpServer> servers = context.getCentralMemory().getMcpServers();

        // Interactive Fallback UI when no arguments are provided
        if (args.length == 0) {
            System.out.println("\n\u001b[36m--- Interactive MCP Server Manager ---\u001b[0m");
            if (servers.isEmpty()) {
                System.out.println("No MCP servers configured yet.");
            } else {
                System.out.println("Current MCP Servers:");
                for (int i = 0; i < servers.size(); i++) {
                    McpServer s = servers.get(i);
                    String status = s.isEnabled() ? "\u001b[32m[ENABLED]\u001b[0m" : "\u001b[31m[DISABLED]\u001b[0m";
                    System.out.println(String.format("  %d. %s (%s) - %s %s", (i + 1), s.getName(), s.getType(), s.getUrl(), status));
                }
            }

            List<String> options = new java.util.ArrayList<>();
            if (!servers.isEmpty()) {
                options.add("Toggle a server's active state");
            }
            options.add("Add a new MCP server");

            String choice = com.mkpro.utils.ConsoleUtils.selectOption(context, "Choose an action:", options);
            if (choice == null) {
                return;
            }

            if (choice.startsWith("Toggle")) {
                List<String> serverNames = servers.stream()
                        .map(s -> s.getName() + " (" + (s.isEnabled() ? "Enabled" : "Disabled") + ")")
                        .collect(java.util.stream.Collectors.toList());
                String selectedServer = com.mkpro.utils.ConsoleUtils.selectOption(context, "Select server to toggle:", serverNames);
                if (selectedServer != null) {
                    int index = serverNames.indexOf(selectedServer);
                    McpServer s = servers.get(index);
                    String idToToggle = s.getId() != null ? s.getId() : s.getName();
                    context.getCentralMemory().toggleMcpServer(idToToggle);
                    System.out.println("\n\u001b[32mToggled MCP server: " + s.getName() + "\u001b[0m");
                    context.rebuildRunner(); // Real-time runner reconstruction
                }
            } else if (choice.startsWith("Add")) {
                org.jline.reader.LineReader reader = context.getLineReader();
                String name = reader.readLine("Enter server name: ").trim();
                if (name.isEmpty()) {
                    System.out.println("Cancelled add.");
                    return;
                }
                String url = reader.readLine("Enter server URL: ").trim();
                if (url.isEmpty()) {
                    System.out.println("Cancelled add.");
                    return;
                }

                // Auto-detection of Figma URL
                McpServer.McpType type = McpServer.McpType.CUSTOM;
                if (url.toLowerCase().contains("figma.com/file") || url.toLowerCase().contains("figma.com/design")) {
                    type = McpServer.McpType.FIGMA;
                    System.out.println("Auto-detected Figma URL. Setting server type to FIGMA.");
                } else {
                    List<String> types = java.util.Arrays.asList("CUSTOM", "FIGMA");
                    String typeChoice = com.mkpro.utils.ConsoleUtils.selectOption(context, "Select server type:", types);
                    if (typeChoice != null) {
                        type = McpServer.McpType.valueOf(typeChoice);
                    }
                }

                McpServer newServer = new McpServer(name, url, type);
                context.getCentralMemory().addMcpServer(newServer);
                System.out.println("\n\u001b[32mAdded MCP server: " + name + "\u001b[0m");
                context.rebuildRunner(); // Real-time runner reconstruction
            }
            return;
        }

        // Subcommand execution
        if (args[0].equalsIgnoreCase("list")) {
            if (servers.isEmpty()) {
                System.out.println("No MCP servers configured.");
            } else {
                System.out.println("MCP Servers:");
                for (int i = 0; i < servers.size(); i++) {
                    McpServer s = servers.get(i);
                    System.out.println(String.format("  [%d] %s", (i + 1), s.toString()));
                }
            }
        } else if (args[0].equalsIgnoreCase("add") && args.length >= 3) {
            String name = args[1];
            String typeStr = "CUSTOM";
            String url = "";

            if (args.length >= 4) {
                typeStr = args[2];
                url = args[3];
            } else {
                url = args[2];
                // Figma URL Auto-detection
                if (url.toLowerCase().contains("figma.com/file") || url.toLowerCase().contains("figma.com/design")) {
                    typeStr = "FIGMA";
                    System.out.println("Auto-detected Figma URL. Setting server type to FIGMA.");
                }
            }

            McpServer.McpType type;
            try {
                type = McpServer.McpType.valueOf(typeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                type = McpServer.McpType.CUSTOM;
            }

            McpServer server = new McpServer(name, url, type);
            context.getCentralMemory().addMcpServer(server);
            System.out.println("Added MCP server: " + server.getName());
            context.rebuildRunner(); // Real-time runner reconstruction
        } else if (args[0].equalsIgnoreCase("toggle") && args.length >= 2) {
            String target = args[1];
            String idToToggle = target;

            // Resolve index if numeric
            try {
                int index = Integer.parseInt(target);
                if (index >= 1 && index <= servers.size()) {
                    McpServer s = servers.get(index - 1);
                    idToToggle = s.getId() != null ? s.getId() : s.getName();
                }
            } catch (NumberFormatException e) {
                // Keep target as ID
            }

            context.getCentralMemory().toggleMcpServer(idToToggle);
            System.out.println("Toggled MCP server: " + idToToggle);
            context.rebuildRunner(); // Real-time runner reconstruction
        } else {
            System.out.println("Usage: mcp [list | add <name> [type] <url> | toggle <id_or_index>]");
        }
    }

    @Override
    public String getName() {
        return "mcp";
    }

    @Override
    public String getDescription() {
        return "Manage MCP servers (list, add, toggle)";
    }
}