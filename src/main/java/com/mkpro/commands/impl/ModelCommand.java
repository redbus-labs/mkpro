package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.models.AgentConfig;
import com.mkpro.config.ModelRegistry;
import com.mkpro.models.Provider;
import com.mkpro.config.ConfigService;
import com.mkpro.utils.ConsoleUtils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.ArrayList;

public class ModelCommand implements Command {
    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        if (args.length == 0) {
            startInteractiveFlow(context);
            return;
        }

        String subCommand = args[0];
        if (subCommand.equalsIgnoreCase("list")) {
            System.out.println("Available Models:");
            System.out.println("GEMINI: " + ModelRegistry.GEMINI_MODELS);
            System.out.println("BEDROCK: " + ModelRegistry.BEDROCK_MODELS);
            System.out.println("AZURE: " + ModelRegistry.AZURE_MODELS);
            System.out.println("OLLAMA: " + ModelRegistry.OLLAMA_MODELS);
        } else if (subCommand.equalsIgnoreCase("provider")) {
            if (args.length >= 4) {
                String providerStr = args[1];
                String settingKey = args[2];
                String value = args[3];
                applyProviderConfig(providerStr, settingKey, value, context);
            } else {
                startProviderInteractiveFlow(context);
            }
        } else if (subCommand.equalsIgnoreCase("set")) {
            if (args.length >= 3) {
                String providerStr = args[1];
                String model = args[2];
                applyModelSet("default", providerStr, model, context);
            } else {
                startInteractiveFlow(context);
            }
        } else {
            System.out.println("Usage: model [list | set <provider> <model> | provider <provider> <setting_key> <value>]");
        }
    }

    private void startInteractiveFlow(MkProContext context) {
        // First select agent
        List<String> agents = new ArrayList<>(context.getAgentConfigs().keySet());
        if (!agents.contains("default")) agents.add(0, "default");
        agents.add(0, "All Agents");
        
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

        applyModelSet(agent, providerStr, model, context);
    }

    private void startProviderInteractiveFlow(MkProContext context) {
        List<String> providers = Arrays.asList("GEMINI", "OLLAMA");
        String providerStr = ConsoleUtils.selectOption(context, "Select a provider to configure:", providers);
        if (providerStr == null) return;

        String settingKey;
        String value;
        if (providerStr.equalsIgnoreCase("GEMINI")) {
            settingKey = "key";
            value = context.getLineReader().readLine("Enter Gemini API Key: ").trim();
        } else {
            settingKey = "url";
            value = context.getLineReader().readLine("Enter Ollama URL (default: http://localhost:11434): ").trim();
            if (value.isEmpty()) value = "http://localhost:11434";
        }
        
        applyProviderConfig(providerStr, settingKey, value, context);
    }

    private void applyProviderConfig(String providerStr, String settingKey, String value, MkProContext context) {
        ConfigService configService = new ConfigService();
        if (providerStr.equalsIgnoreCase("GEMINI") && settingKey.equalsIgnoreCase("key")) {
            configService.saveSetting(ConfigService.PROP_GEMINI_KEY, value);
            context.setApiKey(value);
            System.out.println("Gemini API key updated.");
        } else if (providerStr.equalsIgnoreCase("OLLAMA") && settingKey.equalsIgnoreCase("url")) {
            configService.saveSetting(ConfigService.PROP_OLLAMA_URL, value);
            context.setOllamaUrl(value);
            System.out.println("Ollama URL updated.");
        } else {
            System.out.println("Usage: model provider <GEMINI|OLLAMA> <key|url> <value>");
        }
    }

    private void applyModelSet(String agent, String providerStr, String model, MkProContext context) {
        try {
            Provider provider = Provider.valueOf(providerStr.toUpperCase());
            AgentConfig config = new AgentConfig(provider, model);
            
            if ("All Agents".equalsIgnoreCase(agent)) {
                // Save for default
                context.getCentralMemory().saveAgentConfig("default", config);
                context.getAgentConfigs().put("default", config);
                
                // Save for all individual agents
                for (String agentName : context.getAgentConfigs().keySet()) {
                    if (!agentName.equals("default") && !agentName.equals("All Agents")) {
                        context.getCentralMemory().saveAgentConfig(agentName, config);
                        context.getAgentConfigs().put(agentName, config);
                    }
                }
                System.out.println("Set model for ALL agents to " + model + " (" + provider + ")");
            } else {
                context.getCentralMemory().saveAgentConfig(agent, config);
                context.getAgentConfigs().put(agent, config);
                
                if (agent.equals("default")) {
                    // Update all existing agent configurations in memory
                    for (String agentName : context.getAgentConfigs().keySet()) {
                        context.getAgentConfigs().put(agentName, config);
                    }
                    System.out.println("Set default model to " + model + " (" + provider + ") and updated all agents in memory.");
                } else {
                    System.out.println("Updated config for " + agent + " to " + model + " (" + provider + ")");
                }
            }
            
            // Recreate runner to apply changes
            context.setRunner(context.getAgentManager().createRunner(context.getAgentConfigs(), ""));
            
        } catch (IllegalArgumentException e) {
            System.out.println("Invalid provider: " + providerStr);
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
        return "model";
    }

    @Override
    public String getDescription() {
        return "List available models, set the default model, or configure providers.";
    }
}
