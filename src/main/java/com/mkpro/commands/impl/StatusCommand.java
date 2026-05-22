package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.models.AgentConfig;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

public class StatusCommand implements Command {
    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        Map<String, AgentConfig> configs = context.getAgentConfigs();
        
        System.out.println("System Status:");
        System.out.println(" - API Key: " + (context.getApiKey() != null ? "Set (ends in " + context.getApiKey().substring(Math.max(0, context.getApiKey().length() - 4)) + ")" : "Not Set"));
        System.out.println(" - Ollama Server: " + context.getOllamaUrl());
        System.out.println(" - Active Team: " + context.getCurrentTeam().get());
        System.out.println(" - Active Session: " + (context.getCurrentSession() != null ? context.getCurrentSession().id() : "None"));
        System.out.println(" - Storage Mode: " + context.getCurrentRunnerType().get());
        System.out.println(" - Maker Enabled: " + context.getMakerEnabled().get());
        
        System.out.println("\nActive Team Agents:");
        
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
                System.out.println(String.format(" - %s: %s (%s) [Custom Override]", agent, config.getModelName(), config.getProvider()));
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
                
                System.out.println(String.format(" - %s: %s (%s) [Global Default]", agent, model, provider));
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
