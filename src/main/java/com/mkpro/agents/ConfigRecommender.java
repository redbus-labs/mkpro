package com.mkpro.agents;

import com.mkpro.CentralMemory;
import com.mkpro.models.AgentConfig;
import com.mkpro.models.Provider;
import com.mkpro.commands.impl.OllamaCommand;

import java.io.Console;
import java.util.Map;

/**
 * ConfigRecommender suggests model changes to the user after observing failures.
 * Triggered when a fallback model succeeds — indicates the primary may be underpowered.
 *
 * The recommendation is printed to the terminal with a simple y/N prompt.
 * If approved, the config is updated immediately (takes effect on next delegation).
 * No runner rebuild needed — next createLlm() call will pick up the new config.
 */
public class ConfigRecommender {

    private static final String ANSI_YELLOW = "\u001b[33m";
    private static final String ANSI_GREEN = "\u001b[32m";
    private static final String ANSI_CYAN = "\u001b[36m";
    private static final String ANSI_RESET = "\u001b[0m";

    /**
     * Called when a fallback model succeeds after the primary failed.
     * Recommends updating the primary to the fallback model.
     *
     * @param agentName      The agent that failed
     * @param failedModel    The primary model that failed
     * @param fallbackModel  The fallback model that succeeded
     * @param agentConfigs   The live config map (will be updated if user approves)
     * @param centralMemory  For persisting the change
     */
    public static void recommendAfterFallback(String agentName, String failedModel, String fallbackModel,
                                               Map<String, AgentConfig> agentConfigs, CentralMemory centralMemory) {
        try {
            // Skip if fallback is the same as primary (misconfiguration)
            if (failedModel != null && failedModel.equals(fallbackModel)) {
                return;
            }
            
            // Don't block the stream — just print the recommendation.
            // User can apply via /config command later.
            System.out.println();
            System.out.println(ANSI_CYAN + "💡 Recommendation:" + ANSI_RESET);
            System.out.println(ANSI_YELLOW + "   " + agentName + "'s primary model (" + failedModel + ") failed." + ANSI_RESET);
            System.out.println(ANSI_YELLOW + "   Fallback (" + fallbackModel + ") succeeded." + ANSI_RESET);
            System.out.println(ANSI_YELLOW + "   Run: /config " + agentName + " " + fallbackModel + ANSI_RESET);
            System.out.println();
        } catch (Exception e) {
            // Don't let recommendation failures break the flow
        }
    }

    private static void applyRecommendation(String agentName, String fallbackModel,
                                             Map<String, AgentConfig> agentConfigs, CentralMemory centralMemory) {
        // Parse the fallback model — may be "model@server" or "gemini-xxx"
        String model = fallbackModel;
        String serverUrl = null;
        Provider provider = Provider.OLLAMA;

        if (model.startsWith("gemini")) {
            provider = Provider.GEMINI;
        } else if (model.contains("@")) {
            int atIdx = model.indexOf('@');
            String serverName = model.substring(atIdx + 1);
            model = model.substring(0, atIdx);
            serverUrl = OllamaCommand.resolveServerUrl(serverName, centralMemory);
        }

        AgentConfig newConfig = new AgentConfig(provider, model, serverUrl);
        agentConfigs.put(agentName, newConfig);
        centralMemory.saveAgentConfig(agentName, newConfig);
    }

    private static String readUserInput() {
        try {
            // Try Console first (works in real terminal)
            Console console = System.console();
            if (console != null) {
                return console.readLine();
            }
            // Fallback to System.in
            byte[] buf = new byte[10];
            int read = System.in.read(buf);
            if (read > 0) {
                return new String(buf, 0, read).trim();
            }
        } catch (Exception e) {
            // Non-interactive environment — skip
        }
        return null;
    }
}
