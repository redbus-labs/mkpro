package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.models.AgentConfig;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

import static com.mkpro.MkPro.ANSI_GREEN;
import static com.mkpro.MkPro.ANSI_RESET;

public class StatusCommand implements Command {
    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        Map<String, AgentConfig> configs = context.getAgentConfigs();
        
        System.out.println(ANSI_GREEN + "System Status:" + ANSI_RESET);
        System.out.println(ANSI_GREEN + " - API Key: " + (context.getApiKey() != null ? "Set (ends in " + context.getApiKey().substring(Math.max(0, context.getApiKey().length() - 4)) + ")" : "Not Set") + ANSI_RESET);
        System.out.println(ANSI_GREEN + " - Ollama Server: " + context.getOllamaUrl() + ANSI_RESET);
        System.out.println(ANSI_GREEN + " - Active Team: " + context.getCurrentTeam().get() + ANSI_RESET);
        System.out.println(ANSI_GREEN + " - Active Session: " + (context.getCurrentSession() != null ? context.getCurrentSession().id() : "None") + ANSI_RESET);
        System.out.println(ANSI_GREEN + " - Storage Mode: " + context.getCurrentRunnerType().get() + ANSI_RESET);
        System.out.println(ANSI_GREEN + " - Maker Enabled: " + context.getMakerEnabled().get() + ANSI_RESET);
        
        System.out.println(ANSI_GREEN + "\nActive Team Agents:" + ANSI_RESET);
        
        // Retrieve dynamically active agents from the loaded Team definitions in AgentManager
        List<String> allAgents = new ArrayList<>();
        allAgents.add("Coordinator");
        if (context.getAgentManager() != null && context.getAgentManager().getAgentDefinitions() != null) {
            allAgents.addAll(context.getAgentManager().getAgentDefinitions().keySet());
        } else {
            allAgents.addAll(configs.keySet());
        }
        
        // Remove duplicates/cancel-placeholders, filter default, and sort
        allAgents = allAgents.stream()
                .distinct()
                .filter(a -> !a.equalsIgnoreCase("default") && !a.equalsIgnoreCase("All Agents"))
                .sorted()
                .collect(Collectors.toList());

        for (String agent : allAgents) {
            AgentConfig config = configs.get(agent);
            if (config != null) {
                System.out.println(ANSI_GREEN + String.format(" - %s: %s (%s) [Custom Override]", agent, config.getModelName(), config.getProvider()) + ANSI_RESET);
            } else {
                // Determine defined defaults for the agent
                String model = "llama3";
                String provider = "OLLAMA";
                if (context.getAgentManager() != null && context.getAgentManager().getAgentDefinitions() != null) {
                    var def = context.getAgentManager().getAgentDefinitions().get(agent);
                    if (def != null) {
                        if (def.getModel() != null && !def.getModel().isEmpty()) {
                            model = def.getModel();
                        }
                        if (def.getProvider() != null && !def.getProvider().isEmpty()) {
                            provider = def.getProvider();
                        }
                    }
                }
                
                // If there is a global default config, use it as fallback
                AgentConfig globalDefault = configs.get("default");
                if (globalDefault != null) {
                    model = globalDefault.getModelName();
                    provider = globalDefault.getProvider().name();
                }
                
                System.out.println(ANSI_GREEN + String.format(" - %s: %s (%s) [Global Default]", agent, model, provider) + ANSI_RESET);
            }
        }
    }

    @Override
    public String getName() {
        return "status";
    }

    @Override
    public String getDescription() {
        return "Display system status and active team agents.";
    }
}
