package com.mkpro.routing;

import java.util.List;
import static com.mkpro.ui.AnsiColors.*;
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

    private final MarkovRouter router;
    private final IntentClassifier classifier;
    private final Map<String, MakerState> activeGoals = new ConcurrentHashMap<>();
    private MakerState currentGoal;

    // Event bus (set after construction)
    private volatile com.mkpro.events.MkProEventBus eventBus;

    // Knowledge adequacy checker (set after construction, optional)
    private volatile KnowledgeAdequacyChecker knowledgeChecker;

    // Configuration
    private double autoCompleteThreshold = 0.75;
    private double escalateThreshold = 0.40;
    private int maxRetries = 3;
    private static final int PERIODIC_SAVE_INTERVAL = 10; // Save model every N turns

    // Periodic save state
    private volatile int globalTurnCounter = 0;
    private volatile java.nio.file.Path modelSavePath;
    private volatile com.mkpro.facts.FactEngine factEngine;

    public MakerLoop(MarkovRouter router) {
        this.router = router;
        this.classifier = new IntentClassifier();
    }

    public void setEventBus(com.mkpro.events.MkProEventBus eventBus) {
        this.eventBus = eventBus;
        if (knowledgeChecker != null) knowledgeChecker.setEventBus(eventBus);
    }

    /**
     * Wire knowledge components for proactive gap detection.
     */
    public void setKnowledgeComponents(com.mkpro.knowledge.KnowledgeScheduler scheduler,
                                       com.mkpro.knowledge.KnowledgeStore store,
                                       com.mkpro.knowledge.TopicIndex index) {
        if (knowledgeChecker == null) {
            knowledgeChecker = new KnowledgeAdequacyChecker();
            knowledgeChecker.setEventBus(eventBus);
            knowledgeChecker.setRouter(router);
        }
        knowledgeChecker.setKnowledgeComponents(scheduler, store, index);
    }

    /**
     * Set LLM callback for generating knowledge topic suggestions.
     * Function takes a prompt string and returns LLM response.
     */
    public void setLlmCallback(java.util.function.Function<String, String> callback) {
        if (knowledgeChecker == null) {
            knowledgeChecker = new KnowledgeAdequacyChecker();
            knowledgeChecker.setEventBus(eventBus);
            knowledgeChecker.setRouter(router);
        }
        knowledgeChecker.setLlmCallback(callback);
    }

    /**
     * Called when user sends a new message.
     * Creates a new goal or continues the existing one.
     */
    public MakerState onUserInput(String input) {
        IntentClassifier.TaskCategory category = classifier.classify(input);
        
        // If there's an active goal, check if this is a continuation
        if (currentGoal != null && currentGoal.getPhase() == MakerState.GoalPhase.ACTIVE) {
            // Continue if: same category, generic follow-up, or a continuation phrase
            if (category == currentGoal.getCategory() 
                || category == IntentClassifier.TaskCategory.GENERAL
                || isContinuationPhrase(input)) {
                return currentGoal;
            }
            // Different category — close old goal, start new one
            learnFromCompletion(true); // Assume the old goal was done enough
        }

        // Create a new goal
        currentGoal = new MakerState(input, category, maxRetries);
        activeGoals.put(currentGoal.getGoalId(), currentGoal);
        if (eventBus != null) eventBus.emit(com.mkpro.events.MkProEvent.makerGoal(truncate(input, 60) + " (category: " + category + ")"));
        else System.out.println(ANSI_PURPLE + "  [Maker] New goal: \"" + truncate(input, 60) + "\" (category: " + category + ")" + ANSI_RESET);

        // Proactive knowledge acquisition: check if the goal domain has coverage
        if (knowledgeChecker != null) knowledgeChecker.checkPreGoal(input, currentGoal);

        return currentGoal;
    }

    /**
     * Detect if a user message is a continuation/follow-up to the existing goal.
     * Short imperative phrases like "execute goals", "continue", "next step" etc.
     */
    private boolean isContinuationPhrase(String input) {
        if (input == null) return false;
        String lower = input.toLowerCase().trim();
        
        // Very short inputs are typically follow-ups
        if (lower.split("\\s+").length <= 4) {
            String[] continuationWords = {
                "continue", "proceed", "go ahead", "next", "do it", "execute",
                "run it", "start", "begin", "carry on", "keep going", "resume",
                "yes", "yep", "yeah", "sure", "ok", "okay", "do that", "go on",
                "finish", "complete", "do them", "do this", "do all"
            };
            for (String phrase : continuationWords) {
                if (lower.contains(phrase)) return true;
            }
        }
        return false;
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
            // Layer 2: Pre-delegation hint — suggest expected tools for the predicted agent
            MarkovRouter.RoutingDecision predicted = router.route(currentGoal.getCategory(), null);
            if (predicted.confidence > 0.4) {
                List<MarkovRouter.ToolPrediction> tools = router.getExpectedTools(predicted.agent, currentGoal.getCategory(), 3);
                if (!tools.isEmpty()) {
                    StringBuilder hint = new StringBuilder("[TOOL HINT] For this task type, ");
                    hint.append(predicted.agent).append(" typically uses: ");
                    for (int i = 0; i < tools.size(); i++) {
                        if (i > 0) hint.append(", ");
                        hint.append(tools.get(i).tool);
                    }
                    hint.append(".");
                    // Also append relevant facts on first turn
                    if (factEngine != null) {
                        String facts = factEngine.getRelevantFacts(currentGoal.getGoalDescription());
                        if (facts != null) {
                            hint.append("\n").append(facts);
                            if (eventBus != null) eventBus.emit(com.mkpro.events.MkProEvent.system("Injected verified facts into context"));
                            else System.out.println(ANSI_GREEN + "  [FactEngine] Injected verified facts into context" + ANSI_RESET);
                        }
                    }
                    return hint.toString();
                }
            }
            // No tool hint, but still check for relevant facts on first turn
            if (factEngine != null) {
                String facts = factEngine.getRelevantFacts(currentGoal.getGoalDescription());
                if (facts != null) {
                    String preview = facts.replace("[VERIFIED FACTS]\n", "").replace("\n", " | ").trim();
                    if (preview.length() > 120) preview = preview.substring(0, 120) + "...";
                    if (eventBus != null) eventBus.emit(com.mkpro.events.MkProEvent.system("Facts injected: " + preview));
                    else System.out.println(ANSI_GREEN + "  [FactEngine] Facts injected: " + preview + ANSI_RESET);
                    return facts;
                } else {
                    System.out.println(ANSI_DIM + "  [FactEngine] No relevant facts for: " + currentGoal.getGoalDescription().substring(0, Math.min(60, currentGoal.getGoalDescription().length())) + ANSI_RESET);
                }
            } else {
                System.out.println(ANSI_DIM + "  [FactEngine] Not wired (factEngine is null)" + ANSI_RESET);
            }
            return null;
        }

        String stimulus = currentGoal.generateStimulus(router);

        // Inject verified facts relevant to the goal
        if (factEngine != null) {
            String facts = factEngine.getRelevantFacts(currentGoal.getGoalDescription());
            if (facts != null) {
                stimulus = stimulus + "\n" + facts;
                String preview = facts.replace("[VERIFIED FACTS]\n", "").replace("\n", " | ").trim();
                if (preview.length() > 120) preview = preview.substring(0, 120) + "...";
                if (eventBus != null) eventBus.emit(com.mkpro.events.MkProEvent.system("Facts injected: " + preview));
                else System.out.println(ANSI_GREEN + "  [FactEngine] Facts injected: " + preview + ANSI_RESET);
            }
        }

        return stimulus;
    }

    /**
     * Generate a message for auto-continuation.
     * This allows the Maker to drive the loop without user input.
     */
    public String generateAutoContMessage() {
        if (currentGoal == null || currentGoal.getPhase() == MakerState.GoalPhase.DONE) {
            return null;
        }
        
        // Wrap-up: ask Coordinator to summarize and close
        if (currentGoal.getPhase() == MakerState.GoalPhase.WRAPPING_UP) {
            return "Summarize what has been accomplished so far for the goal: \"" + 
                truncate(currentGoal.getGoalDescription(), 80) + 
                "\". List what was completed and what remains (if anything). Then conclude.";
        }
        
        // Redirect: force delegation to a different agent
        String redirectTarget = currentGoal.getRedirectTarget();
        if (redirectTarget != null) {
            currentGoal.setRedirectTarget(null); // Consume it
            return "Delegate to " + redirectTarget + ": " + currentGoal.getGoalDescription() + 
                "\n\n(Previous approach with " + currentGoal.getLastAgent() + " was not making progress. Try a different strategy.)";
        }
        
        // Normal continue
        String stimulus = currentGoal.generateStimulus(router);
        String base = "Continue with the current task. " + 
            (stimulus != null ? stimulus : "Proceed to the next step.");
        
        return base;
    }

    /**
     * Get the current active goal (for TerminalUI to check state).
     */
    public MakerState getCurrentGoal() {
        return currentGoal;
    }

    /**
     * Called AFTER Coordinator responds.
     * Observes what happened and decides the next action.
     * 
     * @param agentUsed Which agent handled the task
     * @param toolsInvoked List of tools that were called
     * @param success Whether the turn succeeded
     * @param response The full response text (for heuristic completion detection)
     * @return The recommended action
     */
    public MarkovRouter.MakerAction onTurnComplete(String agentUsed, List<String> toolsInvoked, boolean success, String response) {
        if (currentGoal == null) return MarkovRouter.MakerAction.CONTINUE;

        // If we were wrapping up, this turn is the summary — mark complete
        if (currentGoal.getPhase() == MakerState.GoalPhase.WRAPPING_UP) {
            currentGoal.setPhase(MakerState.GoalPhase.DONE);
            learnFromCompletion(true);
            if (eventBus != null) eventBus.emit(com.mkpro.events.MkProEvent.makerComplete(truncate(currentGoal.getGoalDescription(), 60) + " (" + currentGoal.getTurnCount() + " turns)"));
            else System.out.println(ANSI_GREEN + "  ✓ [Maker] Goal wrapped up: \"" + truncate(currentGoal.getGoalDescription(), 60) + "\" (" + currentGoal.getTurnCount() + " turns)" + ANSI_RESET);
            return MarkovRouter.MakerAction.COMPLETE;
        }

        // Record this turn
        currentGoal.recordTurn(agentUsed, toolsInvoked, success);

        // Periodic mid-session model save
        globalTurnCounter++;
        if (globalTurnCounter % PERIODIC_SAVE_INTERVAL == 0 && modelSavePath != null) {
            try {
                router.save(modelSavePath);
            } catch (Exception e) {
                // Non-fatal — model will be saved on exit regardless
            }
        }

        // Track if this is a successful turn after a knowledge-driven retry
        if (success && currentGoal.getPhase() == MakerState.GoalPhase.RETRYING
                && currentGoal.getKnowledgeRetries() > currentGoal.getKnowledgeRetrySuccesses()) {
            currentGoal.incrementKnowledgeRetrySuccesses();
        }

        // Layer 2: Anomaly detection — flag unexpected tool usage
        if (toolsInvoked != null && !toolsInvoked.isEmpty() && agentUsed != null) {
            for (String tool : toolsInvoked) {
                if (router.isAnomalousTool(agentUsed, currentGoal.getCategory(), tool)) {
                    String msg = "⚠ Anomaly: " + agentUsed + " used unexpected tool '" + tool + "' for " + currentGoal.getCategory();
                    if (eventBus != null) eventBus.emit(com.mkpro.events.MkProEvent.system(msg));
                    else System.out.println(ANSI_YELLOW + "  [Maker] " + msg + ANSI_RESET);
                    currentGoal.setAnomalousToolDetected(true);
                    break; // Only flag once per turn
                }
            }
        }

        // Post-turn fact validation: check response for math errors and relationship conflicts
        if (factEngine != null && response != null && !response.isBlank()) {
            java.util.List<String> factIssues = factEngine.validateResponse(response);
            for (String issue : factIssues) {
                if (eventBus != null) eventBus.emit(com.mkpro.events.MkProEvent.system("⚠ Fact: " + issue));
                else System.out.println(ANSI_YELLOW + "  [FactEngine] " + issue + ANSI_RESET);
            }
        }

        // Show turn progress with reasoning
        double completionProb = router.predictCompletion(currentGoal.getCategory(), currentGoal.getToolSequence());
        
        // Heuristic boost: if response contains completion language, boost probability
        if (response != null && completionProb < 0.75) {
            completionProb = Math.max(completionProb, detectCompletionFromResponse(response));
        }
        
        int avgTurns = router.getAvgTurns(currentGoal.getCategory());
        boolean stalled = router.isStalled(currentGoal.getCategory(), currentGoal.getTurnCount());
        
        System.out.println(ANSI_PURPLE + "  [Maker] Turn " + currentGoal.getTurnCount() + 
            "/~" + avgTurns + " | Agent: " + agentUsed + 
            " | " + (success ? "✓" : "✗") +
            " | Completion: " + (int)(completionProb * 100) + "%" +
            (stalled ? " | ⚠ STALLED" : "") + ANSI_RESET);
        if (eventBus != null) eventBus.emit(com.mkpro.events.MkProEvent.makerThought("Turn " + currentGoal.getTurnCount() + "/~" + avgTurns,
            "Agent: " + agentUsed + " | " + (success ? "✓" : "✗") + " | Completion: " + (int)(completionProb * 100) + "%" + (stalled ? " | STALLED" : "")));

        // Show thought process
        StringBuilder thought = new StringBuilder();
        thought.append("  [Maker] Thinking: ");
        if (completionProb >= 0.75) {
            thought.append("Tool pattern matches known completion (").append((int)(completionProb * 100)).append("%) → COMPLETE");
        } else if (!success) {
            thought.append("Last step failed → RETRY (attempt ").append(currentGoal.getRetryCount()).append("/").append(maxRetries).append(")");
        } else if (stalled) {
            // Try redirecting to a different agent before wrapping up
            java.util.Set<String> triedAgents = new java.util.HashSet<>(currentGoal.getAgentSequence());
            String alternative = router.routeExcluding(currentGoal.getCategory(), triedAgents);
            if (alternative != null && currentGoal.getRedirectCount() < 2) {
                thought.append("Stuck with ").append(triedAgents).append(". Redirecting to ").append(alternative).append(".");
                currentGoal.incrementRedirectCount();
                currentGoal.setRedirectTarget(alternative);
                stalled = false; // Don't escalate — we're redirecting
            } else {
                thought.append("All alternatives exhausted → ESCALATE (wrap up)");
            }
        } else {
            // Check stall prediction before deciding CONTINUE
            double stallProb = router.predictStall(currentGoal.getCategory(), currentGoal.getAgentSequence());
            // Layer 2: Anomalous tool usage boosts stall probability
            if (currentGoal.isAnomalousToolDetected()) {
                stallProb = Math.min(1.0, stallProb + 0.25);
            }
            if (stallProb >= 0.6) {
                java.util.Set<String> triedAgents = new java.util.HashSet<>(currentGoal.getAgentSequence());
                String alternative = router.routeExcluding(currentGoal.getCategory(), triedAgents);
                if (alternative != null && currentGoal.getRedirectCount() < 2) {
                    thought.append("⚡ Stall predicted (").append((int)(stallProb * 100)).append("%). Redirecting to ").append(alternative).append(".");
                    currentGoal.incrementRedirectCount();
                    currentGoal.setRedirectTarget(alternative);
                    stalled = false; // Don't escalate, redirect instead
                } else {
                    thought.append("⚡ Stall predicted, no alternatives → ESCALATE");
                    stalled = true;
                }
            } else {
                MarkovRouter.RoutingDecision next = router.route(currentGoal.getCategory(), agentUsed);
                thought.append("Progress OK. Next likely: ").append(next.agent).append(" (").append((int)(next.confidence * 100)).append("%) → CONTINUE");
            }
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

        // Override: if completion detected (by model OR heuristic), force COMPLETE
        if (completionProb >= 0.75 && success) {
            action = MarkovRouter.MakerAction.COMPLETE;
        }

        // Override: Post-turn reactive knowledge acquisition
        // If response shows uncertainty and we haven't maxed out knowledge retries, acquire and retry
        if (action == MarkovRouter.MakerAction.CONTINUE && success && response != null
                && currentGoal.getKnowledgeRetries() < KnowledgeAdequacyChecker.MAX_KNOWLEDGE_ACQUISITIONS_PER_GOAL) {
            if (knowledgeChecker != null && knowledgeChecker.reactiveCheck(response, currentGoal)) {
                action = MarkovRouter.MakerAction.RETRY;
                currentGoal.setPhase(MakerState.GoalPhase.RETRYING);
            }
        }

        // Override: if redirect target is set, force CONTINUE (auto-continue will handle it)
        if (currentGoal.getRedirectTarget() != null) {
            action = MarkovRouter.MakerAction.CONTINUE;
        }

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
                if (eventBus != null) eventBus.emit(com.mkpro.events.MkProEvent.makerComplete(
                    truncate(currentGoal.getGoalDescription(), 60) + " (" + currentGoal.getTurnCount() + " turns)"));
                break;

            case RETRY:
                currentGoal.setPhase(MakerState.GoalPhase.RETRYING);
                System.out.println(ANSI_YELLOW + "  ↻ [Maker] Retrying (attempt " + 
                    currentGoal.getRetryCount() + "/" + maxRetries + ")" + ANSI_RESET);
                break;

            case ESCALATE:
                // Instead of stopping, ask Coordinator to summarize and wrap up
                System.out.println(ANSI_YELLOW + "  ⚠ [Maker] Stall detected (" + currentGoal.getTurnCount() + 
                    " turns). Asking Coordinator to summarize and conclude." + ANSI_RESET);
                // Don't mark as failed — let one more turn happen with wrap-up stimulus
                currentGoal.setPhase(MakerState.GoalPhase.WRAPPING_UP);
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
     * Includes retrospective knowledge adequacy analysis.
     */
    private void learnFromCompletion(boolean success) {
        if (currentGoal == null) return;
        router.recordCompletion(
            currentGoal.getCategory(),
            currentGoal.getToolSequence(),
            success,
            currentGoal.getTurnCount()
        );
        // Record stall pattern on failure/escalation for future prediction
        if (!success && currentGoal.getAgentSequence().size() >= 2) {
            router.recordStall(currentGoal.getCategory(), currentGoal.getAgentSequence());
        }

        // Knowledge adequacy retrospective
        if (knowledgeChecker != null) knowledgeChecker.retrospective(currentGoal, success);
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

    /**
     * Get all goals from this session (for export).
     */
    public java.util.Collection<MakerState> getAllGoals() {
        return activeGoals.values();
    }

    /**
     * Set the model save path for periodic mid-session saves.
     */
    public void setModelSavePath(java.nio.file.Path path) {
        this.modelSavePath = path;
    }

    /**
     * Set the FactEngine for pre-turn injection and post-turn validation.
     */
    public void setFactEngine(com.mkpro.facts.FactEngine engine) {
        this.factEngine = engine;
    }

    /**
     * Backward-compatible overload without response text.
     */
    public MarkovRouter.MakerAction onTurnComplete(String agentUsed, List<String> toolsInvoked, boolean success) {
        return onTurnComplete(agentUsed, toolsInvoked, success, null);
    }

    /**
     * Heuristic completion detection from response text.
     * Looks for language indicating the task is done.
     * Returns a probability (0.0 - 1.0).
     */
    private double detectCompletionFromResponse(String response) {
        if (response == null || response.isEmpty()) return 0.0;
        
        String lower = response.toLowerCase();
        int signals = 0;
        
        // Strong completion signals
        String[] strongSignals = {
            "has been verified", "has been completed", "has been confirmed",
            "is complete", "is done", "is finished", "is ready",
            "successfully", "task complete", "all done",
            "here's the result", "here are the results",
            "confirmed that", "everything is working",
            "operation completed", "operation successful"
        };
        
        for (String signal : strongSignals) {
            if (lower.contains(signal)) signals += 2;
        }
        
        // Moderate completion signals
        String[] moderateSignals = {
            "summary", "in conclusion", "to summarize",
            "the output shows", "as you can see",
            "no issues found", "no errors",
            "working correctly", "functioning properly"
        };
        
        for (String signal : moderateSignals) {
            if (lower.contains(signal)) signals += 1;
        }
        
        // Cap at 1.0, threshold at 3 signal points for high confidence
        return Math.min(1.0, signals / 3.0);
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
