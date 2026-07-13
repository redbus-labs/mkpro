package com.mkpro.routing;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MarkovRouter uses a learned transition matrix to predict which agent should handle a task.
 * 
 * The matrix encodes: P(next_agent | task_category, last_agent)
 * 
 * When confidence exceeds the threshold, the router can bypass the LLM Coordinator
 * and delegate directly — saving tokens and latency.
 *
 * Matrix is persisted to disk and improves with each training update.
 */
public class MarkovRouter {

    private static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.65;

    // transition[category][lastAgent] = {agent -> count}
    private final Map<String, Map<String, Map<String, Integer>>> transitions = new ConcurrentHashMap<>();
    
    // category -> {agent -> count} (ignoring history, just category → agent mapping)
    private final Map<String, Map<String, Integer>> categoryToAgent = new ConcurrentHashMap<>();

    private double confidenceThreshold;
    private int totalObservations = 0;

    public MarkovRouter() {
        this(DEFAULT_CONFIDENCE_THRESHOLD);
    }

    public MarkovRouter(double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }

    /**
     * Result of a routing decision.
     */
    public static class RoutingDecision {
        public final String agent;       // Recommended agent (e.g., "Coder")
        public final double confidence;  // 0.0 to 1.0
        public final boolean shouldRoute; // true if confidence > threshold

        public RoutingDecision(String agent, double confidence, boolean shouldRoute) {
            this.agent = agent;
            this.confidence = confidence;
            this.shouldRoute = shouldRoute;
        }

        @Override
        public String toString() {
            return String.format("%s (%.0f%% confidence, %s)", agent, confidence * 100, 
                shouldRoute ? "ROUTE" : "FALLBACK");
        }
    }

    /**
     * Predict the best agent for a given category and last agent used.
     */
    public RoutingDecision route(IntentClassifier.TaskCategory category, String lastAgent) {
        String catKey = category.name();

        // Try order-1 Markov (category + lastAgent)
        if (lastAgent != null) {
            Map<String, Map<String, Integer>> categoryTransitions = transitions.get(catKey);
            if (categoryTransitions != null) {
                Map<String, Integer> agentCounts = categoryTransitions.get(lastAgent);
                if (agentCounts != null && !agentCounts.isEmpty()) {
                    return pickBest(agentCounts);
                }
            }
        }

        // Fallback: just category → agent (order-0)
        Map<String, Integer> catCounts = categoryToAgent.get(catKey);
        if (catCounts != null && !catCounts.isEmpty()) {
            return pickBest(catCounts);
        }

        // No data — can't route
        return new RoutingDecision("Coordinator", 0.0, false);
    }

    /**
     * Record an observed transition (used during training and live learning).
     */
    public void recordTransition(IntentClassifier.TaskCategory category, String lastAgent, String selectedAgent) {
        String catKey = category.name();

        // Update order-1 matrix
        transitions
            .computeIfAbsent(catKey, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(lastAgent != null ? lastAgent : "_START_", k -> new ConcurrentHashMap<>())
            .merge(selectedAgent, 1, Integer::sum);

        // Update order-0 matrix
        categoryToAgent
            .computeIfAbsent(catKey, k -> new ConcurrentHashMap<>())
            .merge(selectedAgent, 1, Integer::sum);

        totalObservations++;
    }

    /**
     * Get the total number of observations the model has seen.
     */
    public int getTotalObservations() {
        return totalObservations;
    }

    /**
     * Set the confidence threshold for direct routing.
     */
    public void setConfidenceThreshold(double threshold) {
        this.confidenceThreshold = threshold;
    }

    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }

    /**
     * Save the transition matrix to disk.
     */
    public void save(Path path) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(path))) {
            oos.writeObject(new HashMap<>(transitions));
            oos.writeObject(new HashMap<>(categoryToAgent));
            oos.writeInt(totalObservations);
        }
    }

    /**
     * Load the transition matrix from disk.
     */
    @SuppressWarnings("unchecked")
    public void load(Path path) throws IOException, ClassNotFoundException {
        if (!Files.exists(path)) return;
        try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(path))) {
            Map<String, Map<String, Map<String, Integer>>> loaded = (Map<String, Map<String, Map<String, Integer>>>) ois.readObject();
            transitions.putAll(loaded);
            Map<String, Map<String, Integer>> loadedCat = (Map<String, Map<String, Integer>>) ois.readObject();
            categoryToAgent.putAll(loadedCat);
            totalObservations = ois.readInt();
        }
    }

    /**
     * Pick the best agent from a count map with confidence scoring.
     */
    private RoutingDecision pickBest(Map<String, Integer> agentCounts) {
        int total = agentCounts.values().stream().mapToInt(Integer::intValue).sum();
        String bestAgent = null;
        int bestCount = 0;

        for (Map.Entry<String, Integer> entry : agentCounts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                bestAgent = entry.getKey();
            }
        }

        if (bestAgent == null) {
            return new RoutingDecision("Coordinator", 0.0, false);
        }

        double confidence = (double) bestCount / total;
        return new RoutingDecision(bestAgent, confidence, confidence >= confidenceThreshold);
    }

    /**
     * Get a snapshot of the matrix for debugging/display.
     */
    public Map<String, Map<String, Integer>> getCategoryToAgentMatrix() {
        return Collections.unmodifiableMap(categoryToAgent);
    }
}
