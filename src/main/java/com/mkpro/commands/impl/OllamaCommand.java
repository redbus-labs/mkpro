package com.mkpro.commands.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mkpro.CentralMemory;
import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.mkpro.MkPro.*;

/**
 * Manages multiple Ollama server endpoints.
 *
 * Usage:
 *   /ollama list              - Show all configured Ollama servers
 *   /ollama add <name> <url>  - Add a new Ollama endpoint
 *   /ollama remove <name>     - Remove an endpoint
 *   /ollama select <name>     - Set the default active endpoint
 *   /ollama models [name]     - Fetch available models from a server
 *   /ollama status            - Check connectivity of all servers
 */
public class OllamaCommand implements Command {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        CentralMemory memory = context.getCentralMemory();

        if (args.length == 0) {
            showHelp();
            return;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "list":
                listServers(memory, context);
                break;
            case "add":
                if (args.length < 3) {
                    System.out.println(ANSI_YELLOW + "Usage: /ollama add <name> <url>" + ANSI_RESET);
                    System.out.println("  Example: /ollama add gpu-box http://192.168.1.50:11434");
                    return;
                }
                addServer(args[1], args[2], memory);
                break;
            case "remove":
                if (args.length < 2) {
                    System.out.println(ANSI_YELLOW + "Usage: /ollama remove <name>" + ANSI_RESET);
                    return;
                }
                removeServer(args[1], memory);
                break;
            case "select":
                if (args.length < 2) {
                    System.out.println(ANSI_YELLOW + "Usage: /ollama select <name>" + ANSI_RESET);
                    return;
                }
                selectServer(args[1], memory);
                break;
            case "models":
                String serverName = args.length > 1 ? args[1] : null;
                fetchModels(serverName, memory);
                break;
            case "status":
                checkStatus(memory, context);
                break;
            default:
                showHelp();
        }
    }

    private void listServers(CentralMemory memory, MkProContext context) {
        List<String> servers = memory.getOllamaServers();

        System.out.println(ANSI_CYAN + "\n── Ollama Endpoints (all active) ──" + ANSI_RESET);

        if (servers.isEmpty()) {
            System.out.println(ANSI_YELLOW + "  No endpoints configured." + ANSI_RESET);
            System.out.println("  Use '/ollama add <name> <url>' to add endpoints.");
            return;
        }

        for (String entry : servers) {
            String[] parts = parseEntry(entry);
            String name = parts[0];
            String url = parts[1];
            System.out.println(ANSI_GREEN + "  ● " + ANSI_RESET + ANSI_BRIGHT_GREEN + name + ANSI_RESET + " → " + url);
        }
        System.out.println();
        System.out.println("  Assign per-agent: /config <agent> <model>@<server-name>");
        System.out.println("  Example: /config Coder codestral@gpu4090");
        System.out.println();
    }

    private void addServer(String name, String url, CentralMemory memory) {
        // Normalize URL
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }

        List<String> servers = new ArrayList<>(memory.getOllamaServers());

        // Check for duplicate name
        for (String entry : servers) {
            if (parseEntry(entry)[0].equalsIgnoreCase(name)) {
                System.out.println(ANSI_YELLOW + "Server '" + name + "' already exists. Remove it first or use a different name." + ANSI_RESET);
                return;
            }
        }

        servers.add(name + "|" + url);
        memory.saveOllamaServers(servers);

        System.out.println(ANSI_GREEN + "Added Ollama endpoint: " + name + " → " + url + ANSI_RESET);

        // Quick connectivity check
        if (pingServer(url)) {
            System.out.println(ANSI_GREEN + "  ✓ Server is reachable" + ANSI_RESET);
        } else {
            System.out.println(ANSI_YELLOW + "  ⚠ Server not reachable (will retry when used)" + ANSI_RESET);
        }
    }

    private void removeServer(String name, CentralMemory memory) {
        List<String> servers = new ArrayList<>(memory.getOllamaServers());
        boolean found = servers.removeIf(entry -> parseEntry(entry)[0].equalsIgnoreCase(name));

        if (found) {
            memory.saveOllamaServers(servers);

            // If the removed server was selected, clear selection
            String selected = memory.getSelectedOllamaServer();
            String removedUrl = findUrlByName(name, memory.getOllamaServers());
            if (selected != null && selected.equals(removedUrl)) {
                memory.saveSelectedOllamaServer("");
            }

            System.out.println(ANSI_GREEN + "Removed Ollama endpoint: " + name + ANSI_RESET);
        } else {
            System.out.println(ANSI_YELLOW + "Server '" + name + "' not found." + ANSI_RESET);
        }
    }

    private void selectServer(String name, CentralMemory memory) {
        String url = findUrlByName(name, memory.getOllamaServers());
        if (url == null) {
            // Also allow "default" or "local" to reset to config.properties default
            if ("default".equalsIgnoreCase(name) || "local".equalsIgnoreCase(name)) {
                memory.saveSelectedOllamaServer("");
                System.out.println(ANSI_GREEN + "Reset to default Ollama endpoint (from config.properties)." + ANSI_RESET);
                return;
            }
            System.out.println(ANSI_YELLOW + "Server '" + name + "' not found. Use '/ollama list' to see available servers." + ANSI_RESET);
            return;
        }

        memory.saveSelectedOllamaServer(url);
        System.out.println(ANSI_GREEN + "Selected Ollama endpoint: " + name + " → " + url + ANSI_RESET);
        System.out.println("  All agents using OLLAMA provider will now route to this server (unless overridden per-agent).");
    }

    @SuppressWarnings("unchecked")
    private void fetchModels(String serverName, CentralMemory memory) {
        String url;
        if (serverName != null) {
            url = findUrlByName(serverName, memory.getOllamaServers());
            if (url == null) {
                System.out.println(ANSI_YELLOW + "Server '" + serverName + "' not found." + ANSI_RESET);
                return;
            }
        } else {
            String selected = memory.getSelectedOllamaServer();
            url = (selected != null && !selected.isEmpty()) ? selected : "http://localhost:11434";
        }

        System.out.println(ANSI_BLUE + "Fetching models from " + url + "..." + ANSI_RESET);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/api/tags"))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                Map<String, Object> data = mapper.readValue(response.body(), Map.class);
                List<Map<String, Object>> models = (List<Map<String, Object>>) data.get("models");

                if (models != null && !models.isEmpty()) {
                    System.out.println(ANSI_GREEN + "Available models (" + models.size() + "):" + ANSI_RESET);
                    for (Map<String, Object> model : models) {
                        String name = (String) model.get("name");
                        Object sizeObj = model.get("size");
                        String size = sizeObj != null ? formatSize(((Number) sizeObj).longValue()) : "?";
                        System.out.println("  • " + name + " (" + size + ")");
                    }
                } else {
                    System.out.println(ANSI_YELLOW + "No models found on this server." + ANSI_RESET);
                }
            } else {
                System.out.println(ANSI_RED + "Error: HTTP " + response.statusCode() + ANSI_RESET);
            }
        } catch (Exception e) {
            System.out.println(ANSI_RED + "Failed to connect: " + e.getMessage() + ANSI_RESET);
        }
    }

    private void checkStatus(CentralMemory memory, MkProContext context) {
        List<String> servers = memory.getOllamaServers();
        String defaultUrl = context.getOllamaUrl();

        System.out.println(ANSI_CYAN + "\n── Ollama Server Status ──" + ANSI_RESET);

        // Check default
        boolean defaultOk = pingServer(defaultUrl);
        System.out.println((defaultOk ? ANSI_GREEN + "  ✓ " : ANSI_RED + "  ✗ ") +
                "default" + ANSI_RESET + " → " + defaultUrl);

        // Check each registered server
        for (String entry : servers) {
            String[] parts = parseEntry(entry);
            boolean ok = pingServer(parts[1]);
            System.out.println((ok ? ANSI_GREEN + "  ✓ " : ANSI_RED + "  ✗ ") +
                    parts[0] + ANSI_RESET + " → " + parts[1]);
        }
        System.out.println();
    }

    private void showHelp() {
        System.out.println(ANSI_CYAN + "\n── /ollama ── Manage Ollama Endpoints ──" + ANSI_RESET);
        System.out.println("  /ollama list              Show all configured servers");
        System.out.println("  /ollama add <name> <url>  Add a new endpoint");
        System.out.println("  /ollama remove <name>     Remove an endpoint");
        System.out.println("  /ollama select <name>     Set default active endpoint");
        System.out.println("  /ollama models [name]     Fetch models from a server");
        System.out.println("  /ollama status            Check connectivity of all servers");
        System.out.println();
        System.out.println("  Per-agent routing: /config <agent> <model>@<server-name>");
        System.out.println("  Example: /config Coder codestral@gpu-box");
        System.out.println();
    }

    // --- Helpers ---

    private String[] parseEntry(String entry) {
        int sep = entry.indexOf('|');
        if (sep >= 0) {
            return new String[]{entry.substring(0, sep), entry.substring(sep + 1)};
        }
        return new String[]{"unnamed", entry};
    }

    private String findUrlByName(String name, List<String> servers) {
        for (String entry : servers) {
            String[] parts = parseEntry(entry);
            if (parts[0].equalsIgnoreCase(name)) {
                return parts[1];
            }
        }
        return null;
    }

    /**
     * Resolves a server name to a URL. Used externally by ConfigCommand.
     */
    public static String resolveServerUrl(String nameOrUrl, CentralMemory memory) {
        if (nameOrUrl.startsWith("http://") || nameOrUrl.startsWith("https://")) {
            return nameOrUrl;
        }
        List<String> servers = memory.getOllamaServers();
        for (String entry : servers) {
            int sep = entry.indexOf('|');
            if (sep >= 0 && entry.substring(0, sep).equalsIgnoreCase(nameOrUrl)) {
                return entry.substring(sep + 1);
            }
        }
        return null;
    }

    private boolean pingServer(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/api/tags"))
                    .GET()
                    .timeout(Duration.ofSeconds(2))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)) + "MB";
        return String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024));
    }

    @Override
    public String getName() {
        return "ollama";
    }

    @Override
    public String getDescription() {
        return "Manage multiple Ollama server endpoints. Usage: /ollama [list|add|remove|select|models|status]";
    }
}
