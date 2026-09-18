package com.mkpro.routing;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Layer 2 model: Agent → Tool transition probabilities.
 * 
 * Tracks which tools agents use (frequency) and in what order (transitions).
 * Extracted from MarkovRouter to isolate tool-level prediction logic.
 */
public class ToolTransitionModel {

    // toolTransitions[agent][lastTool] = {nextTool → count}
    private final Map<String, Map<String, Map<String, Integer>>> toolTransitions = new ConcurrentHashMap<>();
    // agentToolFrequency[agent+":"+category] = {tool → count}
    private final Map<String, Map<String, Integer>> agentToolFrequency = new ConcurrentHashMap<>();

    /**
     * Tool prediction result.
     */
    public static class ToolPrediction {
        public final String tool;
        public final double confidence;

        public ToolPrediction(String tool, double confidence) {
            this.tool = tool;
            this.confidence = confidence;
        }

        @Override
        public String toString() {
            return tool + " (" + (int)(confidence * 100) + "%)";
        }
    }

    /**
     * Record tool usage for an agent's turn.
     * Updates both frequency (agent+category → tool) and transitions (agent → lastTool → nextTool).
     *
     * @param agent The agent that used the tools
     * @param category The task category
     * @param toolsUsed Ordered list of tools used in this turn
     */
    public void recordToolUsage(String agent, IntentClassifier.TaskCategory category, List<String> toolsUsed) {
        if (agent == null || toolsUsed == null || toolsUsed.isEmpty()) return;

        String freqKey = agent + ":" + category.name();

        // Record frequency: how often this agent uses each tool for this category
        Map<String, Integer> freq = agentToolFrequency.computeIfAbsent(freqKey, k -> new ConcurrentHashMap<>());
        for (String tool : toolsUsed) {
            freq.merge(tool, 1, Integer::sum);
        }

        // Record transitions: tool₁ → tool₂ sequences within this agent
        Map<String, Map<String, Integer>> agentTransitions = 
            toolTransitions.computeIfAbsent(agent, k -> new ConcurrentHashMap<>());

        String lastTool = "_START_"; // Sentinel for first tool in sequence
        for (String tool : toolsUsed) {
            agentTransitions
                .computeIfAbsent(lastTool, k -> new ConcurrentHashMap<>())
                .merge(tool, 1, Integer::sum);
            lastTool = tool;
        }
    }

    /**
     * Predict the next tool an agent will use given its last tool.
     * 
     * @return ToolPrediction with tool name and confidence, or null if no data
     */
    public ToolPrediction predictNextTool(String agent, String lastTool) {
        if (agent == null) return null;
        
        Map<String, Map<String, Integer>> agentTrans = toolTransitions.get(agent);
        if (agentTrans == null) return null;

        String key = (lastTool == null || lastTool.isBlank()) ? "_START_" : lastTool;
        Map<String, Integer> next = agentTrans.get(key);
        if (next == null || next.isEmpty()) return null;

        // Find highest probability transition
        int total = next.values().stream().mapToInt(Integer::intValue).sum();
        String bestTool = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : next.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                bestTool = entry.getKey();
            }
        }

        if (bestTool == null) return null;
        return new ToolPrediction(bestTool, (double) bestCount / total);
    }

    /**
     * Get expected tools for an agent+category, ranked by frequency.
     * Used for stimulus enrichment ("You'll likely need: file_read, file_write, shell").
     *
     * @param agent The agent
     * @param category The task category
     * @param topK Max number of tools to return
     * @return Ordered list of ToolPrediction (highest frequency first)
     */
    public List<ToolPrediction> getExpectedTools(String agent, IntentClassifier.TaskCategory category, int topK) {
        if (agent == null || category == null) return Collections.emptyList();

        String freqKey = agent + ":" + category.name();
        Map<String, Integer> freq = agentToolFrequency.get(freqKey);
        if (freq == null || freq.isEmpty()) return Collections.emptyList();

        int total = freq.values().stream().mapToInt(Integer::intValue).sum();

        List<ToolPrediction> predictions = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : freq.entrySet()) {
            predictions.add(new ToolPrediction(entry.getKey(), (double) entry.getValue() / total));
        }

        predictions.sort((a, b) -> Double.compare(b.confidence, a.confidence));
        return predictions.size() > topK ? predictions.subList(0, topK) : predictions;
    }

    /**
     * Check if an agent's tool usage is anomalous for the given category.
     * Returns true if the tool is not in the agent's typical toolset (< 5% frequency).
     */
    public boolean isAnomalousTool(String agent, IntentClassifier.TaskCategory category, String tool) {
        if (agent == null || tool == null) return false;

        String freqKey = agent + ":" + category.name();
        Map<String, Integer> freq = agentToolFrequency.get(freqKey);
        if (freq == null || freq.isEmpty()) return false; // No data → can't judge

        int total = freq.values().stream().mapToInt(Integer::intValue).sum();
        int toolCount = freq.getOrDefault(tool, 0);

        // Anomalous if never seen or < 5% of total usage
        return (double) toolCount / total < 0.05;
    }

    /**
     * Get the agent→tool frequency map for display (Layer 2 stats).
     */
    public Map<String, Map<String, Integer>> getAgentToolFrequency() {
        return Collections.unmodifiableMap(agentToolFrequency);
    }

    /**
     * Get the tool transitions map for serialization.
     */
    public Map<String, Map<String, Map<String, Integer>>> getToolTransitions() {
        return Collections.unmodifiableMap(toolTransitions);
    }

    /**
     * Set tool transitions from loaded data (used during deserialization).
     */
    public void setToolTransitions(Map<String, Map<String, Map<String, Integer>>> loaded) {
        toolTransitions.clear();
        if (loaded != null) {
            for (var e : loaded.entrySet()) {
                Map<String, Map<String, Integer>> inner = toolTransitions.computeIfAbsent(e.getKey(), k -> new ConcurrentHashMap<>());
                for (var e2 : e.getValue().entrySet()) {
                    inner.put(e2.getKey(), new ConcurrentHashMap<>(e2.getValue()));
                }
            }
        }
    }

    /**
     * Set agent tool frequency from loaded data (used during deserialization).
     */
    public void setAgentToolFrequency(Map<String, Map<String, Integer>> loaded) {
        agentToolFrequency.clear();
        if (loaded != null) {
            for (var e : loaded.entrySet()) {
                agentToolFrequency.put(e.getKey(), new ConcurrentHashMap<>(e.getValue()));
            }
        }
    }
}
