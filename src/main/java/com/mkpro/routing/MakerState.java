package com.mkpro.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MakerState tracks the lifecycle of a single goal through multi-step execution.
 * Records agents used, tools invoked, success/failure at each turn.
 */
public class MakerState {

    public enum GoalPhase {
        ACTIVE,       // In progress
        RETRYING,     // Failed, retrying
        STALLED,      // No progress detected
        COMPLETING,   // Verifying completion
        DONE,         // Verified complete
        ESCALATED     // Handed to user
    }

    private final String goalId;
    private final String goalDescription;
    private final IntentClassifier.TaskCategory category;
    private GoalPhase phase;
    private int turnCount;
    private int retryCount;
    private final int maxRetries;
    private String lastAgent;
    private boolean lastSuccess;
    private final List<String> agentSequence;
    private final List<String> toolSequence;
    private final List<Boolean> turnResults;
    private final long startedAt;
    private long lastActivityAt;

    public MakerState(String goalDescription, IntentClassifier.TaskCategory category) {
        this(goalDescription, category, 3);
    }

    public MakerState(String goalDescription, IntentClassifier.TaskCategory category, int maxRetries) {
        this.goalId = UUID.randomUUID().toString().substring(0, 8);
        this.goalDescription = goalDescription;
        this.category = category;
        this.phase = GoalPhase.ACTIVE;
        this.turnCount = 0;
        this.retryCount = 0;
        this.maxRetries = maxRetries;
        this.lastAgent = null;
        this.lastSuccess = true;
        this.agentSequence = new ArrayList<>();
        this.toolSequence = new ArrayList<>();
        this.turnResults = new ArrayList<>();
        this.startedAt = System.currentTimeMillis();
        this.lastActivityAt = startedAt;
    }

    /**
     * Record what happened in the last turn.
     */
    public void recordTurn(String agent, List<String> toolsInvoked, boolean success) {
        this.turnCount++;
        this.lastAgent = agent;
        this.lastSuccess = success;
        this.lastActivityAt = System.currentTimeMillis();
        
        if (agent != null) agentSequence.add(agent);
        if (toolsInvoked != null) toolSequence.addAll(toolsInvoked);
        turnResults.add(success);

        if (!success) retryCount++;
    }

    /**
     * Generate stimulus text for injection into Coordinator prompt.
     */
    public String generateStimulus(MarkovRouter router) {
        StringBuilder sb = new StringBuilder();
        sb.append("[MAKER CONTEXT]\n");
        sb.append("Goal: \"").append(goalDescription).append("\"\n");
        sb.append("Status: ").append(phase).append(" (turn ").append(turnCount);
        sb.append("/~").append(router.getAvgTurns(category)).append(" avg)\n");

        if (!agentSequence.isEmpty()) {
            sb.append("Sequence: ");
            for (int i = 0; i < agentSequence.size(); i++) {
                if (i > 0) sb.append(" → ");
                sb.append(agentSequence.get(i));
                if (i < turnResults.size() && !turnResults.get(i)) {
                    sb.append("(FAILED)");
                }
            }
            sb.append("\n");
        }

        // Predict next step
        MarkovRouter.RoutingDecision nextPrediction = router.route(category, lastAgent);
        if (nextPrediction.confidence > 0.5) {
            sb.append("Predicted next: ").append(nextPrediction.agent)
              .append(" (").append((int)(nextPrediction.confidence * 100)).append("% confidence)\n");
        }

        // Completion estimate
        double completionProb = router.predictCompletion(category, toolSequence);
        sb.append("Completion estimate: ").append((int)(completionProb * 100)).append("%\n");

        // Action hint
        if (!lastSuccess) {
            sb.append("Action: Last step FAILED. Retry or try different approach.\n");
        } else if (phase == GoalPhase.STALLED) {
            sb.append("Action: STALLED — try a different agent or approach.\n");
        } else if (completionProb > 0.6) {
            sb.append("Action: Nearly complete. Verify and finalize.\n");
        } else {
            sb.append("Action: Continue with the predicted next step.\n");
        }

        return sb.toString();
    }

    /**
     * Check if max retries exceeded.
     */
    public boolean isMaxRetriesExceeded() {
        return retryCount >= maxRetries;
    }

    // ═══ Getters/Setters ═══

    public String getGoalId() { return goalId; }
    public String getGoalDescription() { return goalDescription; }
    public IntentClassifier.TaskCategory getCategory() { return category; }
    public GoalPhase getPhase() { return phase; }
    public void setPhase(GoalPhase phase) { this.phase = phase; }
    public int getTurnCount() { return turnCount; }
    public int getRetryCount() { return retryCount; }
    public String getLastAgent() { return lastAgent; }
    public boolean isLastSuccess() { return lastSuccess; }
    public List<String> getAgentSequence() { return agentSequence; }
    public List<String> getToolSequence() { return toolSequence; }
    public long getStartedAt() { return startedAt; }
}
