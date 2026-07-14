package com.mkpro.routing;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MakerLoop is the goal-driven supervisor that ensures tasks are completed, not just attempted.
 * 
 * It sits between user input and the Coordinator, injecting per-turn stimulus
 * that drives agents forward through multi-step workflows until verified completion.
 * 
 * Key behaviors:
 * - Creates goals from user input
 * - Generates stimulus context injected before each Coordinator turn
 * - Observes turn results (agent, tools, success/fail)
 * - Decides: CONTINUE / RETRY / ESCALATE / COMPLETE
 * - Learns from completed sequences to improve future decisions
 */
public class MakerLoop {

    private static final String ANSI_PURPLE = "\u001b[35m";
    private static final String ANSI_GREEN = "\u001b[32m";
    private static final String ANSI_YELLOW = "\u001b[33m";
    private static final String ANSI_RED = "\u001b[31m";
    private static final String ANSI_RESET = "\u001b[0m";

    private final MarkovRouter router;
    private final IntentClassifier classifier;
    private final Map<String, MakerState> activeGoals = new ConcurrentHashMap<>();
    private MakerState currentGoal;

    // Configuration
    private double autoCompleteThreshold = 0.75;
    private double escalateThreshold = 0.40;
    private int maxRetries = 3;

    public MakerLoop(MarkovRouter router) {
        this.router = router;
        this.classifier = new IntentClassifier();
    }

    /**
     * Called when user sends a new message.
     * Creates a new goal or continues the existing one.
     */
    public MakerState onUserInput(String input) {
        IntentClassifier.TaskCategory category = classifier.classify(input);
        
        // If there's an active goal in the SAME category, continue it
        if (currentGoal != null && currentGoal.getPhase() == MakerState.GoalPhase.ACTIVE) {
            // Check if this is a continuation (same category or generic follow-up)
            if (category == currentGoal.getCategory() || category == IntentClassifier.TaskCategory.GENERAL) {
                return currentGoal;
            }
            // Different category — close old goal, start new one
            learnFromCompletion(true); // Assume the old goal was done enough
        }

        // Create a new goal
        currentGoal = new MakerState(input, category, maxRetries);
        activeGoals.put(currentGoal.getGoalId(), currentGoal);
        System.out.println(ANSI_PURPLE + "  [Maker] New goal: \"" + truncate(input, 60) + 
            "\" (category: " + category + ")" + ANSI_RESET);

        return currentGoal;
    }

    /**
     * Called BEFORE sending to Coordinator.
     * Returns stimulus text to inject, or null if no active goal.
     */
    public String generatePreTurnStimulus() {
        if (currentGoal == null || currentGoal.getPhase() == MakerState.GoalPhase.DONE) {
            return null;
        }

        // Only inject stimulus after the first turn (let the first delegation happen naturally)
        if (currentGoal.getTurnCount() == 0) {
            return null;
        }

        return currentGoal.generateStimulus(router);
    }

    /**
     * Called AFTER Coordinator responds.
     * Observes what happened and decides the next action.
     * 
     * @param agentUsed Which agent handled the task
     * @param toolsInvoked List of tools that were called
     * @param success Whether the turn succeeded
     * @return The recommended action
     */
    public MarkovRouter.MakerAction onTurnComplete(String agentUsed, List<String> toolsInvoked, boolean success) {
        if (currentGoal == null) return MarkovRouter.MakerAction.CONTINUE;

        // Record this turn
        currentGoal.recordTurn(agentUsed, toolsInvoked, success);

        // Show turn progress with reasoning
        double completionProb = router.predictCompletion(currentGoal.getCategory(), currentGoal.getToolSequence());
        int avgTurns = router.getAvgTurns(currentGoal.getCategory());
        boolean stalled = router.isStalled(currentGoal.getCategory(), currentGoal.getTurnCount());
        
        System.out.println(ANSI_PURPLE + "  [Maker] Turn " + currentGoal.getTurnCount() + 
            "/~" + avgTurns + " | Agent: " + agentUsed + 
            " | " + (success ? "✓" : "✗") +
            " | Completion: " + (int)(completionProb * 100) + "%" +
            (stalled ? " | ⚠ STALLED" : "") + ANSI_RESET);

        // Show thought process
        StringBuilder thought = new StringBuilder();
        thought.append("  [Maker] Thinking: ");
        if (completionProb >= 0.75) {
            thought.append("Tool pattern matches known completion (").append((int)(completionProb * 100)).append("%) → COMPLETE");
        } else if (!success) {
            thought.append("Last step failed → RETRY (attempt ").append(currentGoal.getRetryCount()).append("/").append(maxRetries).append(")");
        } else if (stalled) {
            thought.append("Turn count exceeds ").append((int)(avgTurns * 2)).append(" (2x avg) → ESCALATE");
        } else {
            MarkovRouter.RoutingDecision next = router.route(currentGoal.getCategory(), agentUsed);
            thought.append("Progress OK. Next likely: ").append(next.agent).append(" (").append((int)(next.confidence * 100)).append("%) → CONTINUE");
        }
        System.out.println(ANSI_PURPLE + thought + ANSI_RESET);

        // Get Markov recommendation
        MarkovRouter.MakerAction action = router.recommendAction(
            currentGoal.getCategory(),
            currentGoal.getTurnCount(),
            agentUsed,
            success,
            currentGoal.getToolSequence()
        );

        // Override: if max retries exceeded, escalate
        if (action == MarkovRouter.MakerAction.RETRY && currentGoal.isMaxRetriesExceeded()) {
            action = MarkovRouter.MakerAction.ESCALATE;
        }

        // Execute the action
        executeAction(action);

        return action;
    }

    /**
     * Execute the Maker's decision.
     */
    private void executeAction(MarkovRouter.MakerAction action) {
        switch (action) {
            case COMPLETE:
                currentGoal.setPhase(MakerState.GoalPhase.DONE);
                learnFromCompletion(true);
                System.out.println(ANSI_GREEN + "  ✓ [Maker] Goal complete: \"" + 
                    truncate(currentGoal.getGoalDescription(), 60) + "\" (" + 
                    currentGoal.getTurnCount() + " turns)" + ANSI_RESET);
                break;

            case RETRY:
                currentGoal.setPhase(MakerState.GoalPhase.RETRYING);
                System.out.println(ANSI_YELLOW + "  ↻ [Maker] Retrying (attempt " + 
                    currentGoal.getRetryCount() + "/" + maxRetries + ")" + ANSI_RESET);
                break;

            case ESCALATE:
                currentGoal.setPhase(MakerState.GoalPhase.ESCALATED);
                System.out.println(ANSI_RED + "  ⚠ [Maker] Escalating to user — " + 
                    (currentGoal.isMaxRetriesExceeded() ? "max retries exceeded" : "stalled") + ANSI_RESET);
                learnFromCompletion(false);
                break;

            case CONTINUE:
                // Normal flow — goal still active
                break;
        }
    }

    /**
     * Generate a retry stimulus — tells the Coordinator what failed and what to try.
     */
    public String generateRetryStimulus() {
        if (currentGoal == null || currentGoal.getPhase() != MakerState.GoalPhase.RETRYING) {
            return null;
        }

        StringBuilder sb = new StringBuilder("[MAKER RETRY]\n");
        sb.append("The previous step FAILED. ");
        sb.append("Goal: \"").append(truncate(currentGoal.getGoalDescription(), 80)).append("\"\n");
        sb.append("Failed agent: ").append(currentGoal.getLastAgent()).append("\n");
        sb.append("Retry count: ").append(currentGoal.getRetryCount()).append("/").append(maxRetries).append("\n");

        // Suggest a different agent if available
        MarkovRouter.RoutingDecision alt = router.route(currentGoal.getCategory(), currentGoal.getLastAgent());
        if (alt.confidence > 0.4 && !alt.agent.equals(currentGoal.getLastAgent())) {
            sb.append("Suggestion: Try ").append(alt.agent).append(" instead.\n");
        } else {
            sb.append("Suggestion: Try a different approach with the same agent.\n");
        }

        // Reset phase to active for next turn
        currentGoal.setPhase(MakerState.GoalPhase.ACTIVE);
        return sb.toString();
    }

    /**
     * Mark the current goal as done by user confirmation.
     */
    public void markComplete() {
        if (currentGoal != null) {
            currentGoal.setPhase(MakerState.GoalPhase.DONE);
            learnFromCompletion(true);
        }
    }

    /**
     * Mark current goal as abandoned/failed.
     */
    public void markFailed() {
        if (currentGoal != null) {
            currentGoal.setPhase(MakerState.GoalPhase.DONE);
            learnFromCompletion(false);
        }
    }

    /**
     * Learn from a completed goal sequence.
     */
    private void learnFromCompletion(boolean success) {
        if (currentGoal == null) return;
        router.recordCompletion(
            currentGoal.getCategory(),
            currentGoal.getToolSequence(),
            success,
            currentGoal.getTurnCount()
        );
    }

    /**
     * Check if there's an active goal in progress.
     */
    public boolean hasActiveGoal() {
        return currentGoal != null && 
               currentGoal.getPhase() != MakerState.GoalPhase.DONE &&
               currentGoal.getPhase() != MakerState.GoalPhase.ESCALATED;
    }

    /**
     * Get the current goal state.
     */
    public MakerState getCurrentGoal() {
        return currentGoal;
    }

    /**
     * Reset — clear current goal (user starts fresh topic).
     */
    public void reset() {
        if (currentGoal != null && currentGoal.getPhase() != MakerState.GoalPhase.DONE) {
            learnFromCompletion(false); // Abandoned = failed
        }
        currentGoal = null;
    }

    // Configuration
    public void setAutoCompleteThreshold(double t) { this.autoCompleteThreshold = t; }
    public void setEscalateThreshold(double t) { this.escalateThreshold = t; }
    public void setMaxRetries(int r) { this.maxRetries = r; }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
