package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.models.AgentConfig;
import com.mkpro.models.Provider;
import com.mkpro.utils.ConsoleUtils;
import com.mkpro.config.ModelRegistry;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.ArrayList;

public class ConfigCommand implements Command {
    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        if (args.length == 0) {
            startInteractiveFlow(context);
            return;
        }

        String subCommand = args[0];
        if (subCommand.equalsIgnoreCase("list")) {
            Map<String, AgentConfig> configs = context.getCentralMemory().getAllAgentConfigs();
            AgentConfig globalDefault = configs.get("default");
            
            System.out.println("Agent Configurations:");
            if (globalDefault != null) {
                System.out.println(String.format(" - [Global Default]: %s (%s)", globalDefault.getModelName(), globalDefault.getProvider()));
            } else {
                System.out.println(" - [Global Default]: Not set (using internal defaults)");
            }

            configs.forEach((agent, config) -> {
                if (!agent.equals("default")) {
                    System.out.println(String.format(" - %s: %s (%s)", agent, config.getModelName(), config.getProvider()));
                }
            });
        } else if (subCommand.equalsIgnoreCase("reset") && args.length >= 2) {
            String agent = args[1];
            context.getCentralMemory().deleteAgentConfig(agent);
            context.getAgentConfigs().remove(agent);
            System.out.println("Reset configuration for " + agent + ". It will now fall back to the Global Default.");
            context.rebuildRunner(); // Rebuild cleanly to apply resets
        } else if (subCommand.equalsIgnoreCase("set")) {
            if (args.length >= 4) {
                String agent = args[1];
                String providerStr = args[2];
                String model = args[3];
                applyConfig(agent, providerStr, model, context);
            } else {
                startInteractiveFlow(context);
            }
        } else {
            System.out.println("Usage: config [list | set <agent> <provider> <model> | reset <agent>]");
        }
    }

    private void startInteractiveFlow(MkProContext context) {
        // Retrieve dynamically active agents from the loaded Team definitions in AgentManager
        List<String> agents = new ArrayList<>();
        agents.add("default");
        if (context.getAgentManager() != null && context.getAgentManager().getAgentDefinitions() != null) {
            agents.addAll(context.getAgentManager().getAgentDefinitions().keySet());
        } else {
            agents.addAll(context.getAgentConfigs().keySet());
        }
        if (!agents.contains("Coordinator")) {
            agents.add("Coordinator");
        }
        
        // Remove duplicates/cancel-placeholders and sort
        agents = agents.stream()
                .distinct()
                .filter(a -> !a.equalsIgnoreCase("default") && !a.equalsIgnoreCase("All Agents"))
                .collect(Collectors.toList());
        agents.add(0, "default");
        agents.add("All Agents");
        
        String agent = ConsoleUtils.selectOption(context, "Select agent to configure:", agents);
        if (agent == null) return;

        List<String> providers = Arrays.stream(Provider.values()).map(Enum::name).collect(Collectors.toList());
        String providerStr = ConsoleUtils.selectOption(context, "Select a provider:", providers);
        if (providerStr == null) return;

        List<String> models = getModelsForProvider(providerStr);
        String model;
        if (models.isEmpty()) {
            model = context.getLineReader().readLine("Enter model name: ").trim();
        } else {
            model = ConsoleUtils.selectOption(context, "Select a model for " + providerStr + ":", models);
        }
        if (model == null || model.isEmpty()) return;

        applyConfig(agent, providerStr, model, context);
    }

    private void applyConfig(String agent, String providerStr, String model, MkProContext context) {
        try {
            Provider provider = Provider.valueOf(providerStr.toUpperCase());
            AgentConfig config = new AgentConfig(provider, model);
            
            if ("All Agents".equalsIgnoreCase(agent)) {
                // Save for default
                context.getCentralMemory().saveAgentConfig("default", config);
                context.getAgentConfigs().put("default", config);
                
                // Get all active dynamic team agents to ensure updates are broadcast to all team roles
                List<String> activeAgents = new ArrayList<>();
                activeAgents.add("Coordinator");
                if (context.getAgentManager() != null && context.getAgentManager().getAgentDefinitions() != null) {
                    activeAgents.addAll(context.getAgentManager().getAgentDefinitions().keySet());
                } else {
                    activeAgents.addAll(context.getAgentConfigs().keySet());
                }
                
                // Save for all individual active agents
                for (String agentName : activeAgents) {
                    if (!agentName.equalsIgnoreCase("default") && !agentName.equalsIgnoreCase("All Agents")) {
                        context.getCentralMemory().saveAgentConfig(agentName, config);
                        context.getAgentConfigs().put(agentName, config);
                    }
                }
                System.out.println("Updated config for ALL agents to " + model + " (" + provider + ")");
            } else {
                context.getCentralMemory().saveAgentConfig(agent, config);
                context.getAgentConfigs().put(agent, config);
                System.out.println("Updated config for " + agent + " to " + model + " (" + provider + ")");
            }
            
            // Re-create the runner cleanly and seamlessly swap sessions
            context.rebuildRunner();
            
        } catch (IllegalArgumentException e) {
            System.out.println("Invalid provider: " + providerStr + ". Available: " + Arrays.toString(Provider.values()));
        }
    }

    private List<String> getModelsForProvider(String provider) {
        switch (provider.toUpperCase()) {
            case "GEMINI": return ModelRegistry.GEMINI_MODELS;
            case "BEDROCK": return ModelRegistry.BEDROCK_MODELS;
            case "AZURE": return ModelRegistry.AZURE_MODELS;
            case "OLLAMA": return ModelRegistry.OLLAMA_MODELS;
            default: return Arrays.asList();
        }
    }

    @Override
    public String getName() {
        return "config";
    }

    @Override
    public String getDescription() {
        return "Manage agent configurations (list, set, reset).";
    }
}