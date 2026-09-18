package com.mkpro.maker.distillation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tracks strategy similarity across successive execution trajectories for a goal.
 * Uses Jaccard similarity of tool and action sequences to detect thrashing/looping.
 */
public class StrategyDivergenceTracker {

    public static final double DEFAULT_LOOPING_THRESHOLD = 0.70;

    /**
     * Calculates Jaccard similarity between two execution trajectories based on
     * action/tool unigrams, bigram transitions, and action signatures.
     *
     * @param current current trajectory
     * @param previous previous trajectory
     * @return similarity score between 0.0 (completely divergent) and 1.0 (identical)
     */
    public double calculateSimilarity(ExecutionTrajectory current, ExecutionTrajectory previous) {
        if (current == null && previous == null) {
            return 1.0;
        }
        if (current == null || previous == null) {
            return 0.0;
        }

        List<TrajectoryStep> steps1 = current.getSteps();
        List<TrajectoryStep> steps2 = previous.getSteps();

        boolean empty1 = (steps1 == null || steps1.isEmpty());
        boolean empty2 = (steps2 == null || steps2.isEmpty());

        if (empty1 && empty2) {
            return 1.0;
        }
        if (empty1 || empty2) {
            return 0.0;
        }

        Set<String> features1 = extractTrajectoryFeatures(steps1);
        Set<String> features2 = extractTrajectoryFeatures(steps2);

        if (features1.isEmpty() && features2.isEmpty()) {
            return 1.0;
        }

        Set<String> intersection = new HashSet<>(features1);
        intersection.retainAll(features2);

        Set<String> union = new HashSet<>(features1);
        union.addAll(features2);

        if (union.isEmpty()) {
            return 1.0;
        }

        return (double) intersection.size() / (double) union.size();
    }

    /**
     * Checks if current trajectory is looping/thrashing relative to previous trajectory
     * using the default threshold of 0.70.
     *
     * @param current current execution trajectory
     * @param previous previous execution trajectory
     * @return true if similarity >= 0.70, false otherwise
     */
    public boolean isStrategyLooping(ExecutionTrajectory current, ExecutionTrajectory previous) {
        return isStrategyLooping(current, previous, DEFAULT_LOOPING_THRESHOLD);
    }

    /**
     * Checks if current trajectory is looping/thrashing relative to previous trajectory
     * against a specific similarity threshold.
     *
     * @param current current execution trajectory
     * @param previous previous execution trajectory
     * @param threshold similarity threshold in range [0.0, 1.0]
     * @return true if similarity >= threshold, false otherwise
     */
    public boolean isStrategyLooping(ExecutionTrajectory current, ExecutionTrajectory previous, double threshold) {
        double similarity = calculateSimilarity(current, previous);
        return similarity >= threshold;
    }

    /**
     * Extracts token and n-gram features from a trajectory step list.
     * Includes individual action tokens (positional & categorical) and transition pairs.
     */
    private Set<String> extractTrajectoryFeatures(List<TrajectoryStep> steps) {
        Set<String> features = new HashSet<>();

        String prevAction = "^START^";
        for (int i = 0; i < steps.size(); i++) {
            TrajectoryStep step = steps.get(i);
            String agent = (step.getAgentName() != null) ? step.getAgentName().trim() : "agent";
            String tool = (step.getActionOrTool() != null) ? step.getActionOrTool().trim() : "tool";
            String actionKey = agent + ":" + tool;

            // Unigram feature
            features.add("ACTION:" + actionKey);
            // Positional unigram feature (captures phase alignment)
            features.add("POS_" + i + ":" + actionKey);
            // Bigram transition feature (captures workflow sequence)
            features.add("TRANSITION:" + prevAction + "->" + actionKey);

            prevAction = actionKey;
        }

        features.add("TRANSITION:" + prevAction + "->^END^");
        return features;
    }
}
