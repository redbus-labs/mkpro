package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.models.AgentConfig;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

import static com.mkpro.MkPro.*;

public class StatusCommand implements Command {
    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        Map<String, AgentConfig> configs = context.getAgentConfigs();
        
        System.out.println(ANSI_GREEN + "System Status:" + ANSI_RESET);
        System.out.println(ANSI_GREEN + " - API Key: " + (context.getApiKey() != null ? "Set (ends in " + context.getApiKey().substring(Math.max(0, context.getApiKey().length() - 4)) + ")" : "Not Set") + ANSI_RESET);
        System.out.println(ANSI_GREEN + " - Active Team: " + context.getCurrentTeam().get() + ANSI_RESET);
        System.out.println(ANSI_GREEN + " - Active Session: " + (context.getCurrentSession() != null ? context.getCurrentSession().id() : "None") + ANSI_RESET);
        System.out.println(ANSI_GREEN + " - Storage Mode: " + context.getCurrentRunnerType().get() + ANSI_RESET);
        System.out.println(ANSI_GREEN + " - Maker Enabled: " + context.getMakerEnabled().get() + ANSI_RESET);
        
        // Ollama Endpoints
        List<String> ollamaServers = context.getCentralMemory().getOllamaServers();
        System.out.println(ANSI_CYAN + "\nOllama Endpoints:" + ANSI_RESET);
        if (ollamaServers.isEmpty()) {
            System.out.println("  (none configured)");
        } else {
            for (String entry : ollamaServers) {
                int sep = entry.indexOf('|');
                if (sep >= 0) {
                    String name = entry.substring(0, sep);
                    String url = entry.substring(sep + 1);
                    System.out.println(ANSI_GREEN + "  ● " + name + ANSI_RESET + " → " + url);
                }
            }
        }
        
        // Active Team Agents
        System.out.println(ANSI_CYAN + "\nActive Team Agents:" + ANSI_RESET);
        
        List<String> allAgents = new ArrayList<>();
        allAgents.add("Coordinator");
        if (context.getAgentManager() != null && context.getAgentManager().getAgentDefinitions() != null) {
            allAgents.addAll(context.getAgentManager().getAgentDefinitions().keySet());
        } else {
            allAgents.addAll(configs.keySet());
        }
        
        allAgents = allAgents.stream()
                .distinct()
                .filter(a -> !a.equalsIgnoreCase("default") && !a.equalsIgnoreCase("All Agents"))
                .sorted()
                .collect(Collectors.toList());

        for (String agent : allAgents) {
            AgentConfig config = configs.get(agent);
            if (config != null) {
                String serverInfo = "";
                if (config.hasServerUrl()) {
                    String serverName = resolveServerName(config.getServerUrl(), ollamaServers);
                    serverInfo = " @" + serverName;
                }
                // Get fallback info
                String fallbackInfo = "";
                if (context.getAgentManager() != null && context.getAgentManager().getAgentDefinitions() != null) {
                    var def = context.getAgentManager().getAgentDefinitions().get(agent);
                    if (def != null && def.getFallbackModel() != null && !def.getFallbackModel().isEmpty()) {
                        fallbackInfo = " → fallback: " + def.getFallbackModel();
                    }
                }
                System.out.println(ANSI_GREEN + String.format(" - %s: %s (%s%s)%s", 
                    agent, config.getModelName(), config.getProvider(), serverInfo, fallbackInfo) + ANSI_RESET);
            } else {
                String model = "llama3";
                String provider = "OLLAMA";
                if (context.getAgentManager() != null && context.getAgentManager().getAgentDefinitions() != null) {
                    var def = context.getAgentManager().getAgentDefinitions().get(agent);
                    if (def != null) {
                        if (def.getModel() != null && !def.getModel().isEmpty()) model = def.getModel();
                        if (def.getProvider() != null && !def.getProvider().isEmpty()) provider = def.getProvider();
                    }
                }
                AgentConfig globalDefault = configs.get("default");
                if (globalDefault != null) {
                    model = globalDefault.getModelName();
                    provider = globalDefault.getProvider().name();
                }
                System.out.println(ANSI_YELLOW + String.format(" - %s: %s (%s) [default]", agent, model, provider) + ANSI_RESET);
            }
        }
        
        // Show global fallback
        String globalFallback = context.getCentralMemory().getMemory("__global_fallback_model");
        if (globalFallback != null && !globalFallback.isEmpty()) {
            System.out.println(ANSI_CYAN + "\nGlobal Fallback: " + globalFallback + ANSI_RESET);
        } else {
            System.out.println(ANSI_YELLOW + "\nGlobal Fallback: not set (use '/config fallback default <model>')" + ANSI_RESET);
        }
    }

    private String resolveServerName(String url, List<String> servers) {
        for (String entry : servers) {
            int sep = entry.indexOf('|');
            if (sep >= 0) {
                String name = entry.substring(0, sep);
                String serverUrl = entry.substring(sep + 1);
                if (serverUrl.equals(url)) return name;
            }
        }
        // Shorten URL if no name match
        return url.replace("http://", "").replace("https://", "");
    }

    @Override
    public String getName() {
        return "status";
    }

    @Override
    public String getDescription() {
        return "Display system status, Ollama endpoints, and active team agents.";
    }
}
