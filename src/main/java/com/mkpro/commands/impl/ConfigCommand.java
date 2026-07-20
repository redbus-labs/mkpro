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

        // Support legacy main/master syntax: /config <agent> <provider> [model]
        if (!subCommand.equalsIgnoreCase("list") && !subCommand.equalsIgnoreCase("set") 
                && !subCommand.equalsIgnoreCase("reset") && !subCommand.equalsIgnoreCase("fallback")) {
            if (args.length >= 2) {
                String agent = args[0];
                String providerStr = args[1];
                String model;
                if (args.length >= 3) {
                    model = args[2];
                } else {
                    if (providerStr.equalsIgnoreCase("GEMINI")) {
                        model = "gemini-1.5-flash";
                    } else {
                        model = "llama3";
                    }
                }
                applyConfig(agent, providerStr, model, context);
                return;
            } else {
                System.out.println("Usage: config [list | set <agent> <provider> <model> | reset <agent>]");
                System.out.println("Or legacy: config <agent> <provider> [model]");
                return;
            }
        }

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
        } else if (subCommand.equalsIgnoreCase("fallback")) {
            if (args.length < 3) {
                System.out.println("Usage: /config fallback <agent> <model[@server]>");
                System.out.println("  Example: /config fallback Coder gemini-2.0-flash");
                System.out.println("  Example: /config fallback Tester codestral@gpu4090");
                System.out.println("  Use '/config fallback <agent> none' to remove fallback.");
                return;
            }
            String agent = args[1];
            String fallbackModel = args[2];
            setFallbackModel(agent, fallbackModel, context);
        } else {
            System.out.println("Usage: config [list | set <agent> <provider> <model> | fallback <agent> <model> | reset <agent>]");
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
        agents.add(0, "All Agents");
        
        String agent = ConsoleUtils.selectOption(context, "Select agent to configure:", agents);
        if (agent == null) return;

        // Build provider list with individual Ollama endpoints
        List<String> providers = new ArrayList<>();
        List<String> ollamaServers = context.getCentralMemory().getOllamaServers();
        if (!ollamaServers.isEmpty()) {
            for (String entry : ollamaServers) {
                int sep = entry.indexOf('|');
                if (sep >= 0) {
                    String name = entry.substring(0, sep);
                    providers.add("OLLAMA [" + name + "]");
                }
            }
        } else {
            providers.add("OLLAMA");
        }
        providers.add("GEMINI");
        providers.add("BEDROCK");
        providers.add("SARVAM");
        providers.add("AZURE");
        providers.add("NVIDIA");
        
        String providerChoice = ConsoleUtils.selectOption(context, "Select a provider:", providers);
        if (providerChoice == null) return;

        // Parse the provider choice — extract server name if OLLAMA [xxx]
        String providerStr;
        String serverName = null;
        if (providerChoice.startsWith("OLLAMA")) {
            providerStr = "OLLAMA";
            if (providerChoice.contains("[")) {
                serverName = providerChoice.substring(providerChoice.indexOf('[') + 1, providerChoice.indexOf(']'));
            }
        } else {
            providerStr = providerChoice;
        }
        if (providerStr == null) return;

        List<String> models = getModelsForProvider(providerStr);
        // If a specific Ollama server was chosen, fetch models from that server
        if (serverName != null && providerStr.equals("OLLAMA")) {
            String serverUrl = OllamaCommand.resolveServerUrl(serverName, context.getCentralMemory());
            if (serverUrl != null) {
                List<String> serverModels = com.mkpro.config.ModelRegistry.fetchModelsFromServer(serverUrl);
                if (!serverModels.isEmpty()) {
                    models = serverModels;
                }
            }
        }
        String model;
        if (models.isEmpty()) {
            model = context.getLineReader().readLine("Enter model name: ").trim();
        } else {
            model = ConsoleUtils.selectOption(context, "Select a model for " + providerStr + ":", models);
        }
        if (model == null || model.isEmpty()) return;

        // Append server name for Ollama per-endpoint routing
        if (serverName != null && !model.contains("@")) {
            model = model + "@" + serverName;
        }

        applyConfig(agent, providerStr, model, context);
    }

    private void applyConfig(String agent, String providerStr, String model, MkProContext context) {
        try {
            Provider provider = Provider.valueOf(providerStr.toUpperCase());

            if (!validateProviderCredentials(provider, model)) {
                return;
            }
            
            // Parse model@server syntax for Ollama per-agent routing
            String serverUrl = null;
            if (provider == Provider.OLLAMA && model.contains("@")) {
                int atIdx = model.indexOf('@');
                String serverName = model.substring(atIdx + 1);
                model = model.substring(0, atIdx);
                
                // Resolve server name to URL
                serverUrl = OllamaCommand.resolveServerUrl(serverName, context.getCentralMemory());
                if (serverUrl == null) {
                    System.out.println("Unknown Ollama server: '" + serverName + "'. Use '/ollama list' to see available servers.");
                    return;
                }
            }
            
            AgentConfig config = new AgentConfig(provider, model, serverUrl);
            
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

    /**
     * Validate provider credentials before persisting config / rebuilding the runner.
     * Prevents a failed Azure/Sarvam init from wiping the active runner.
     */
    private boolean validateProviderCredentials(Provider provider, String model) {
        if (provider == Provider.AZURE) {
            List<String> missing = new ArrayList<>();
            if (isBlankEnv("AZURE_OPENAI_API_KEY")) {
                missing.add("AZURE_OPENAI_API_KEY");
            }
            String lower = model != null ? model.toLowerCase() : "";
            boolean realtime = lower.contains("realtime");
            if (realtime) {
                if (lower.contains("translate")) {
                    if (isBlankEnv("AZURE_TRANSLATE_ENDPOINT") && isBlankEnv("AZURE_REALTIME_ENDPOINT")) {
                        missing.add("AZURE_TRANSLATE_ENDPOINT (or AZURE_REALTIME_ENDPOINT)");
                    }
                } else if (isBlankEnv("AZURE_REALTIME_ENDPOINT")) {
                    missing.add("AZURE_REALTIME_ENDPOINT");
                }
            } else if (isBlankEnv("AZURE_RESPONSE_ENDPOINT") && isBlankEnv("AZURE_MODEL_ENDPOINT")) {
                missing.add("AZURE_RESPONSE_ENDPOINT");
            }
            if (!missing.isEmpty()) {
                System.out.println("\u001b[31mCannot configure AZURE — missing environment variables:\u001b[0m");
                for (String m : missing) {
                    System.out.println("  - " + m);
                }
                System.out.println("Export them in your shell before starting mkpro, e.g.:");
                System.out.println("  export AZURE_OPENAI_API_KEY=...");
                System.out.println("  export AZURE_RESPONSE_ENDPOINT=https://<resource>.openai.azure.com/openai/v1/responses");
                return false;
            }
        } else if (provider == Provider.SARVAM) {
            if (isBlankEnv("SARVAM_API_KEY")) {
                System.out.println("\u001b[31mCannot configure SARVAM — SARVAM_API_KEY is not set.\u001b[0m");
                System.out.println("  export SARVAM_API_KEY=...");
                return false;
            }
        } else if (provider == Provider.GEMINI) {
            // Gemini key may come from config.properties; warn only.
            // Actual validation happens via Gemini client.
        }
        return true;
    }

    private static boolean isBlankEnv(String name) {
        String val = System.getenv(name);
        return val == null || val.isBlank();
    }

    private void setFallbackModel(String agentName, String fallbackModel, MkProContext context) {
        // Global fallback — applies to all agents without a specific fallback
        if ("default".equalsIgnoreCase(agentName) || "all".equalsIgnoreCase(agentName) || "global".equalsIgnoreCase(agentName)) {
            if ("none".equalsIgnoreCase(fallbackModel) || "clear".equalsIgnoreCase(fallbackModel)) {
                context.getCentralMemory().saveMemory("__global_fallback_model", "");
                System.out.println("Cleared global fallback model.");
            } else {
                context.getCentralMemory().saveMemory("__global_fallback_model", fallbackModel);
                System.out.println("Set global fallback → " + fallbackModel);
                System.out.println("  Any agent without a specific fallback will use this if primary fails.");
            }
            return;
        }

        if (context.getAgentManager() == null || context.getAgentManager().getAgentDefinitions() == null) {
            System.out.println("Agent manager not initialized.");
            return;
        }

        var def = context.getAgentManager().getAgentDefinitions().get(agentName);
        if (def == null) {
            // Try case-insensitive match
            for (var entry : context.getAgentManager().getAgentDefinitions().entrySet()) {
                if (entry.getKey().equalsIgnoreCase(agentName)) {
                    def = entry.getValue();
                    agentName = entry.getKey();
                    break;
                }
            }
        }

        if (def == null) {
            System.out.println("Agent '" + agentName + "' not found. Use '/config list' to see available agents.");
            return;
        }

        if ("none".equalsIgnoreCase(fallbackModel) || "clear".equalsIgnoreCase(fallbackModel)) {
            def.setFallbackModel(null);
            System.out.println("Removed fallback model for " + agentName + ".");
        } else {
            // Validate server name if model@server syntax
            if (fallbackModel.contains("@")) {
                String serverName = fallbackModel.substring(fallbackModel.indexOf('@') + 1);
                String url = OllamaCommand.resolveServerUrl(serverName, context.getCentralMemory());
                if (url == null && !fallbackModel.startsWith("gemini")) {
                    System.out.println("Warning: Server '" + serverName + "' not found in registered endpoints.");
                }
            }
            def.setFallbackModel(fallbackModel);
            System.out.println("Set fallback for " + agentName + " → " + fallbackModel);
            System.out.println("  If primary model fails, " + agentName + " will retry with: " + fallbackModel);
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
