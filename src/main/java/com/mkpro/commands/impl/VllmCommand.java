package com.mkpro.commands.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mkpro.CentralMemory;
import com.mkpro.commands.Command;
import com.mkpro.config.ConfigService;
import com.mkpro.config.ModelRegistry;
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
 * Manages multiple OpenAI/vLLM compatible server endpoints.
 *
 * Usage:
 *   /vllm list                                   - Show all configured vLLM servers
 *   /vllm add <name> <url> [key] [maxContext]    - Add a new vLLM endpoint
 *   /vllm context <name> <maxContextTokens>      - Set max context tokens for an endpoint
 *   /vllm remove <name>                          - Remove an endpoint
 *   /vllm select <name>                          - Set the default active endpoint
 *   /vllm models [name]                          - Fetch available models from a server
 *   /vllm status                                 - Check connectivity of all servers
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
                    System.out.println(ANSI_YELLOW + "Usage: /vllm add <name> <url> [apiKey] [maxContext]" + ANSI_RESET);
                    return;
                }
                addServer(
                        args[1],
                        args[2],
                        args.length > 3 ? args[3] : null,
                        args.length > 4 ? args[4] : null,
                        memory);
                break;
            case "context":
                if (args.length < 3) {
                    System.out.println(ANSI_YELLOW + "Usage: /vllm context <name> <maxContextTokens>" + ANSI_RESET);
                    return;
                }
                updateServerContext(args[1], args[2], memory, context);
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
            String url = parts.length > 1 ? parts[1] : "";
            boolean isSelected = url.equals(selected);
            String keyStatus = (parts.length > 2 && !parts[2].isEmpty()) ? "Key set" : "No key";
            int maxCtx = resolveServerMaxContext(name, memory);

            String prefix = isSelected ? ANSI_GREEN + "  ● " : "    ";
            System.out.println(prefix + ANSI_BRIGHT_GREEN + name + ANSI_RESET + " → " + url
                    + ANSI_DIM + " (" + keyStatus + ", context: " + maxCtx + ")" + ANSI_RESET);
        }
    }

    private void addServer(String name, String url, String apiKey, String maxContextArg, CentralMemory memory) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.contains("/v1") && !url.endsWith("/v1")) {
            url += "/v1";
        }

        Integer explicitMaxContext = null;
        if (maxContextArg != null && !maxContextArg.isBlank()) {
            try {
                explicitMaxContext = Integer.parseInt(maxContextArg.trim());
            } catch (NumberFormatException e) {
                System.out.println(ANSI_YELLOW + "  ⚠ Invalid maxContext '" + maxContextArg + "', ignoring." + ANSI_RESET);
            }
        }

        List<String> models = ModelRegistry.fetchOpenAiModelsFromServer(url, apiKey);
        if (explicitMaxContext == null) {
            Integer detected = detectMaxModelLenFromServer(url, apiKey);
            if (detected != null && detected > 0) {
                explicitMaxContext = detected;
            }
        }

        List<String> servers = new ArrayList<>(memory.getOpenAiServers());
        servers.removeIf(e -> parseEntry(e)[0].equalsIgnoreCase(name));

        String entry = buildServerEntry(name, url, apiKey, explicitMaxContext);
        servers.add(entry);
        memory.saveOpenAiServers(servers);

        int resolvedCtx = resolveServerMaxContext(name, memory);
        System.out.println(ANSI_GREEN + "Added endpoint: " + name + " → " + url + " (context: " + resolvedCtx + ")" + ANSI_RESET);

        if (!models.isEmpty()) {
            System.out.println(ANSI_GREEN + "  ✓ Server reachable, hosted " + models.size() + " models." + ANSI_RESET);
        } else {
            System.out.println(ANSI_YELLOW + "  ⚠ Could not reach server or no models found." + ANSI_RESET);
        }
    }

    private void updateServerContext(String name, String maxContextStr, CentralMemory memory, MkProContext context) {
        int maxContext;
        try {
            maxContext = Integer.parseInt(maxContextStr.trim());
        } catch (NumberFormatException e) {
            System.out.println(ANSI_YELLOW + "Invalid maxContextTokens value: " + maxContextStr + ANSI_RESET);
            return;
        }

        List<String> servers = new ArrayList<>(memory.getOpenAiServers());
        boolean updated = false;
        for (int i = 0; i < servers.size(); i++) {
            String[] p = parseEntry(servers.get(i));
            if (p[0].equalsIgnoreCase(name)) {
                String url = p.length > 1 ? p[1] : "";
                String apiKey = p.length > 2 ? p[2] : null;
                servers.set(i, buildServerEntry(p[0], url, apiKey, maxContext));
                updated = true;
                break;
            }
        }

        if (!updated) {
            System.out.println(ANSI_YELLOW + "Server not found: " + name + ANSI_RESET);
            return;
        }

        memory.saveOpenAiServers(servers);
        System.out.println(ANSI_GREEN + "Updated max context for " + name + " → " + maxContext + " tokens" + ANSI_RESET);
        context.rebuildRunner();
    }

    @SuppressWarnings("unchecked")
    private static Integer detectMaxModelLenFromServer(String serverUrl, String apiKey) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(serverUrl + "/models"))
                    .GET()
                    .timeout(Duration.ofSeconds(5));
            if (apiKey != null && !apiKey.isEmpty()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Map<String, Object> data = mapper.readValue(response.body(), Map.class);
                List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("data");
                if (list != null && !list.isEmpty()) {
                    Object maxLenObj = list.get(0).get("max_model_len");
                    if (maxLenObj instanceof Number num) {
                        return num.intValue();
                    } else if (maxLenObj != null) {
                        return Integer.parseInt(maxLenObj.toString().trim());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String buildServerEntry(String name, String url, String apiKey, Integer maxContext) {
        String safeKey = apiKey != null ? apiKey : "";
        if (maxContext != null) {
            return name + "|" + url + "|" + safeKey + "|" + maxContext;
        } else if (!safeKey.isEmpty()) {
            return name + "|" + url + "|" + safeKey;
        } else {
            return name + "|" + url;
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
            String name = parts[0];
            String url = parts.length > 1 ? parts[1] : "";
            String apiKey = (parts.length > 2 && !parts[2].isEmpty()) ? parts[2] : null;
            List<String> models = ModelRegistry.fetchOpenAiModelsFromServer(url, apiKey);
            int maxCtx = resolveServerMaxContext(name, memory);
            System.out.println((!models.isEmpty() ? ANSI_GREEN + "✓ " : ANSI_RED + "✗ ")
                    + name + " (" + models.size() + " models, context: " + maxCtx + ")" + ANSI_RESET);
        }
    }

    private void showHelp() {
        System.out.println("Usage: /vllm [list|add|context|remove|select|models|status]");
    }

    private static String[] parseEntry(String entry) {
        return entry.split("\\|", -1);
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
            if ((p[0].equalsIgnoreCase(nameOrUrl) || (p.length > 1 && p[1].equalsIgnoreCase(nameOrUrl))) && p.length > 2 && !p[2].isEmpty()) {
                return p[2];
            }
        }
        return null;
    }

    public static String resolveServerName(String url, CentralMemory memory) {
        for (String e : memory.getOpenAiServers()) {
            String[] p = parseEntry(e);
            if (p.length > 1 && p[1].equalsIgnoreCase(url)) return p[0];
        }
        return null;
    }

    public static int resolveServerMaxContext(String nameOrUrl, CentralMemory memory) {
        String matchedName = null;
        if (memory != null && nameOrUrl != null) {
            for (String e : memory.getOpenAiServers()) {
                String[] p = parseEntry(e);
                boolean nameMatch = p[0].equalsIgnoreCase(nameOrUrl);
                boolean urlMatch = p.length > 1 && p[1].equalsIgnoreCase(nameOrUrl);
                if (nameMatch || urlMatch) {
                    matchedName = p[0];
                    if (p.length > 3 && !p[3].isBlank()) {
                        try {
                            return Integer.parseInt(p[3].trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    break;
                }
            }
        }
        ConfigService configService = new ConfigService();
        if (matchedName != null && !matchedName.isBlank()) {
            return configService.getOpenAiMaxContext(matchedName, ConfigService.DEFAULT_OPENAI_MAX_CONTEXT);
        }
        return configService.getOpenAiMaxContext(null, ConfigService.DEFAULT_OPENAI_MAX_CONTEXT);
    }

    @Override public String getName() { return "vllm"; }
    @Override public String getDescription() { return "Manage OpenAI/vLLM endpoints"; }
}