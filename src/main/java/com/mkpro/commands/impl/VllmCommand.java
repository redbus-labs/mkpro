package com.mkpro.commands.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mkpro.CentralMemory;
import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.config.ModelRegistry;

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
 * Manages multiple OpenAI/vLLM compatible server endpoints.
 *
 * Usage:
 *   /vllm list              - Show all configured vLLM servers
 *   /vllm add <name> <url> [key] - Add a new vLLM endpoint
 *   /vllm remove <name>     - Remove an endpoint
 *   /vllm select <name>     - Set the default active endpoint
 *   /vllm models [name]     - Fetch available models from a server
 *   /vllm status            - Check connectivity of all servers
 */
public class VllmCommand implements Command {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
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
                    System.out.println(ANSI_YELLOW + "Usage: /vllm add <name> <url> [apiKey]" + ANSI_RESET);
                    return;
                }
                addServer(args[1], args[2], args.length > 3 ? args[3] : null, memory);
                break;
            case "remove":
                if (args.length < 2) {
                    System.out.println(ANSI_YELLOW + "Usage: /vllm remove <name>" + ANSI_RESET);
                    return;
                }
                removeServer(args[1], memory);
                break;
            case "select":
                if (args.length < 2) {
                    System.out.println(ANSI_YELLOW + "Usage: /vllm select <name>" + ANSI_RESET);
                    return;
                }
                selectServer(args[1], memory, context);
                break;
            case "models":
                String serverName = args.length > 1 ? args[1] : null;
                fetchModels(serverName, memory);
                break;
            case "status":
                checkStatus(memory);
                break;
            default:
                showHelp();
        }
    }

    private void listServers(CentralMemory memory, MkProContext context) {
        List<String> servers = memory.getOpenAiServers();
        String selected = memory.getSelectedOpenAiServer();

        System.out.println(ANSI_CYAN + "\n── OpenAI/vLLM Endpoints ──" + ANSI_RESET);

        if (servers.isEmpty()) {
            System.out.println(ANSI_YELLOW + "  No endpoints configured." + ANSI_RESET);
            return;
        }

        for (String entry : servers) {
            String[] parts = parseEntry(entry);
            String name = parts[0];
            String url = parts[1];
            boolean isSelected = url.equals(selected);
            String keyStatus = (parts.length > 2 && !parts[2].isEmpty()) ? " (Key set)" : " (No key)";
            
            String prefix = isSelected ? ANSI_GREEN + "  ● " : "    ";
            System.out.println(prefix + ANSI_BRIGHT_GREEN + name + ANSI_RESET + " → " + url + ANSI_DIM + keyStatus + ANSI_RESET);
        }
    }

    private void addServer(String name, String url, String apiKey, CentralMemory memory) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.contains("/v1") && !url.endsWith("/v1")) {
            url += "/v1";
        }

        List<String> servers = new ArrayList<>(memory.getOpenAiServers());
        servers.removeIf(e -> parseEntry(e)[0].equalsIgnoreCase(name));
        
        String entry = name + "|" + url + (apiKey != null ? "|" + apiKey : "");
        servers.add(entry);
        memory.saveOpenAiServers(servers);

        System.out.println(ANSI_GREEN + "Added endpoint: " + name + " → " + url + ANSI_RESET);
        
        List<String> models = ModelRegistry.fetchOpenAiModelsFromServer(url, apiKey);
        if (!models.isEmpty()) {
            System.out.println(ANSI_GREEN + "  ✓ Server reachable, hosted " + models.size() + " models." + ANSI_RESET);
        } else {
            System.out.println(ANSI_YELLOW + "  ⚠ Could not reach server or no models found." + ANSI_RESET);
        }
    }

    private void removeServer(String name, CentralMemory memory) {
        List<String> servers = new ArrayList<>(memory.getOpenAiServers());
        boolean found = servers.removeIf(entry -> parseEntry(entry)[0].equalsIgnoreCase(name));
        if (found) {
            memory.saveOpenAiServers(servers);
            String selected = memory.getSelectedOpenAiServer();
            // Basic clearing if needed
            System.out.println(ANSI_GREEN + "Removed endpoint: " + name + ANSI_RESET);
        }
    }

    private void selectServer(String name, CentralMemory memory, MkProContext context) {
        String url = resolveServerUrl(name, memory);
        if (url == null) {
            System.out.println(ANSI_YELLOW + "Server not found." + ANSI_RESET);
            return;
        }
        memory.saveSelectedOpenAiServer(url);
        System.out.println(ANSI_GREEN + "Selected: " + name + ANSI_RESET);
        context.rebuildRunner();
    }

    private void fetchModels(String serverName, CentralMemory memory) {
        String url = (serverName != null) ? resolveServerUrl(serverName, memory) : memory.getSelectedOpenAiServer();
        String apiKey = (serverName != null) ? resolveServerApiKey(serverName, memory) : null;
        
        if (url == null) {
            System.out.println(ANSI_YELLOW + "No server found/selected." + ANSI_RESET);
            return;
        }

        List<String> models = ModelRegistry.fetchOpenAiModelsFromServer(url, apiKey);
        System.out.println(ANSI_CYAN + "Models on " + url + ":" + ANSI_RESET);
        for (String m : models) System.out.println("  • " + m);
    }

    private void checkStatus(CentralMemory memory) {
        for (String entry : memory.getOpenAiServers()) {
            String[] parts = parseEntry(entry);
            List<String> models = ModelRegistry.fetchOpenAiModelsFromServer(parts[1], parts.length > 2 ? parts[2] : null);
            System.out.println((!models.isEmpty() ? ANSI_GREEN + "✓ " : ANSI_RED + "✗ ") + parts[0] + " (" + models.size() + " models)");
        }
    }

    private void showHelp() {
        System.out.println("Usage: /vllm [list|add|remove|select|models|status]");
    }

    private static String[] parseEntry(String entry) {
        return entry.split("\\|");
    }

    public static String resolveServerUrl(String nameOrUrl, CentralMemory memory) {
        if (nameOrUrl.startsWith("http")) return nameOrUrl;
        for (String e : memory.getOpenAiServers()) {
            String[] p = parseEntry(e);
            if (p[0].equalsIgnoreCase(nameOrUrl)) return p[1];
        }
        return null;
    }

    public static String resolveServerApiKey(String nameOrUrl, CentralMemory memory) {
        for (String e : memory.getOpenAiServers()) {
            String[] p = parseEntry(e);
            if ((p[0].equalsIgnoreCase(nameOrUrl) || (p.length > 1 && p[1].equalsIgnoreCase(nameOrUrl))) && p.length > 2) return p[2];
        }
        return null;
    }

    public static String resolveServerName(String url, CentralMemory memory) {
        for (String e : memory.getOpenAiServers()) {
            String[] p = parseEntry(e);
            if (p[1].equalsIgnoreCase(url)) return p[0];
        }
        return null;
    }

    @Override public String getName() { return "vllm"; }
    @Override public String getDescription() { return "Manage OpenAI/vLLM endpoints"; }
}
