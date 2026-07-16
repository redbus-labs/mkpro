package com.mkpro.routing;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Maker system:
 * - MakerState lifecycle and stimulus generation
 * - MakerLoop decisions (continue, retry, escalate, complete)
 * - MarkovRouter completion prediction and stall detection
 */
public class MakerSystemTest {

    private MarkovRouter router;
    private MakerLoop maker;

    @BeforeEach
    void setUp() {
        router = new MarkovRouter();
        maker = new MakerLoop(router);
    }

    // ==========================================================================
    // MakerState tests
    // ==========================================================================

    @Test
    void newStateIsActive() {
        MakerState state = new MakerState("Add auth", IntentClassifier.TaskCategory.CODING);
        assertEquals(MakerState.GoalPhase.ACTIVE, state.getPhase());
        assertEquals(0, state.getTurnCount());
        assertEquals(0, state.getRetryCount());
    }

    @Test
    void recordTurnIncrementsTurnCount() {
        MakerState state = new MakerState("Add auth", IntentClassifier.TaskCategory.CODING);
        state.recordTurn("Coder", List.of("file_read", "file_write"), true);
        assertEquals(1, state.getTurnCount());
        assertEquals("Coder", state.getLastAgent());
        assertTrue(state.isLastSuccess());
    }

    @Test
    void failedTurnIncrementsRetryCount() {
        MakerState state = new MakerState("Fix bug", IntentClassifier.TaskCategory.CODING);
        state.recordTurn("Tester", List.of("shell"), false);
        assertEquals(1, state.getRetryCount());
        assertFalse(state.isLastSuccess());
    }

    @Test
    void maxRetriesDetected() {
        MakerState state = new MakerState("Fix bug", IntentClassifier.TaskCategory.CODING, 2);
        state.recordTurn("Coder", List.of(), false);
        state.recordTurn("Coder", List.of(), false);
        assertTrue(state.isMaxRetriesExceeded());
    }

    @Test
    void agentSequenceTracked() {
        MakerState state = new MakerState("Build feature", IntentClassifier.TaskCategory.CODING);
        state.recordTurn("Architect", List.of("file_read"), true);
        state.recordTurn("Coder", List.of("file_write"), true);
        state.recordTurn("Tester", List.of("shell"), true);
        assertEquals(List.of("Architect", "Coder", "Tester"), state.getAgentSequence());
    }

    @Test
    void toolSequenceAccumulates() {
        MakerState state = new MakerState("Build feature", IntentClassifier.TaskCategory.CODING);
        state.recordTurn("Coder", List.of("file_read", "file_write"), true);
        state.recordTurn("Tester", List.of("shell"), true);
        assertEquals(List.of("file_read", "file_write", "shell"), state.getToolSequence());
    }

    @Test
    void stimulusContainsGoalDescription() {
        MakerState state = new MakerState("Add JWT auth", IntentClassifier.TaskCategory.CODING);
        state.recordTurn("Architect", List.of(), true);
        String stimulus = state.generateStimulus(router);
        assertTrue(stimulus.contains("Add JWT auth"));
        assertTrue(stimulus.contains("[MAKER CONTEXT]"));
    }

    @Test
    void stimulusShowsSequence() {
        MakerState state = new MakerState("Task", IntentClassifier.TaskCategory.CODING);
        state.recordTurn("Architect", List.of(), true);
        state.recordTurn("Coder", List.of(), false);
        String stimulus = state.generateStimulus(router);
        assertTrue(stimulus.contains("Architect"));
        assertTrue(stimulus.contains("Coder"));
        assertTrue(stimulus.contains("FAILED"));
    }

    // ==========================================================================
    // MakerLoop tests
    // ==========================================================================

    @Test
    void onUserInputCreatesGoal() {
        MakerState state = maker.onUserInput("write unit tests");
        assertNotNull(state);
        assertEquals(MakerState.GoalPhase.ACTIVE, state.getPhase());
        assertTrue(maker.hasActiveGoal());
    }

    @Test
    void noStimulusOnFirstTurn() {
        maker.onUserInput("implement feature");
        String stimulus = maker.generatePreTurnStimulus();
        assertNull(stimulus); // First turn — let it flow naturally
    }

    @Test
    void stimulusAfterFirstTurn() {
        maker.onUserInput("implement feature");
        maker.onTurnComplete("Architect", List.of("file_read"), true);
        String stimulus = maker.generatePreTurnStimulus();
        assertNotNull(stimulus);
        assertTrue(stimulus.contains("[MAKER CONTEXT]"));
    }

    @Test
    void continueOnNormalTurn() {
        maker.onUserInput("implement feature");
        MarkovRouter.MakerAction action = maker.onTurnComplete("Coder", List.of("file_write"), true);
        assertEquals(MarkovRouter.MakerAction.CONTINUE, action);
    }

    @Test
    void retryOnFailure() {
        maker.onUserInput("fix the bug");
        MarkovRouter.MakerAction action = maker.onTurnComplete("Tester", List.of("shell"), false);
        assertEquals(MarkovRouter.MakerAction.RETRY, action);
    }

    @Test
    void escalateAfterMaxRetries() {
        maker.setMaxRetries(2);
        maker.onUserInput("fix complex issue");
        maker.onTurnComplete("Coder", List.of(), false); // retry 1
        MarkovRouter.MakerAction action = maker.onTurnComplete("Coder", List.of(), false); // retry 2 - exceeds max
        assertEquals(MarkovRouter.MakerAction.ESCALATE, action);
        // Next turn wraps up (Coordinator summarizes)
        MarkovRouter.MakerAction wrapUp = maker.onTurnComplete("Coordinator", List.of(), true);
        assertEquals(MarkovRouter.MakerAction.COMPLETE, wrapUp);
    }

    @Test
    void completeWhenCompletionPatternMatches() {
        // Train the router to recognize this as a complete pattern
        router.recordCompletion(IntentClassifier.TaskCategory.CODING, 
            List.of("file_read", "file_write", "shell"), true, 3);
        router.recordCompletion(IntentClassifier.TaskCategory.CODING, 
            List.of("file_read", "file_write", "shell"), true, 4);
        router.recordCompletion(IntentClassifier.TaskCategory.CODING, 
            List.of("file_read", "file_write", "shell"), true, 3);

        maker.onUserInput("implement the service");
        maker.onTurnComplete("Coder", List.of("file_read", "file_write"), true);
        MarkovRouter.MakerAction action = maker.onTurnComplete("Tester", List.of("shell"), true);
        assertEquals(MarkovRouter.MakerAction.COMPLETE, action);
    }

    @Test
    void retryStimulusGenerated() {
        maker.onUserInput("fix bug");
        maker.onTurnComplete("Coder", List.of(), false); // triggers RETRY
        String stimulus = maker.generateRetryStimulus();
        assertNotNull(stimulus);
        assertTrue(stimulus.contains("[MAKER RETRY]"));
        assertTrue(stimulus.contains("FAILED"));
    }

    @Test
    void noRetryStimulusWhenNotRetrying() {
        maker.onUserInput("write code");
        maker.onTurnComplete("Coder", List.of(), true);
        String stimulus = maker.generateRetryStimulus();
        assertNull(stimulus);
    }

    @Test
    void markCompleteEndGoal() {
        maker.onUserInput("task");
        maker.onTurnComplete("Coder", List.of(), true);
        maker.markComplete();
        assertFalse(maker.hasActiveGoal());
    }

    @Test
    void resetClearsGoal() {
        maker.onUserInput("task");
        maker.reset();
        assertFalse(maker.hasActiveGoal());
    }

    @Test
    void continuingExistingGoal() {
        MakerState first = maker.onUserInput("build the feature");
        maker.onTurnComplete("Architect", List.of(), true);
        MakerState second = maker.onUserInput("continue");
        // Should return the same goal, not create new
        assertSame(first, second);
    }

    // ==========================================================================
    // MarkovRouter completion prediction tests
    // ==========================================================================

    @Test
    void predictCompletionReturnsZeroWithNoData() {
        double prob = router.predictCompletion(IntentClassifier.TaskCategory.CODING, List.of("file_write"));
        assertEquals(0.0, prob);
    }

    @Test
    void predictCompletionAfterTraining() {
        // Train: file_write + shell = COMPLETE
        router.recordCompletion(IntentClassifier.TaskCategory.TESTING, 
            List.of("file_write", "shell"), true, 3);
        router.recordCompletion(IntentClassifier.TaskCategory.TESTING, 
            List.of("file_write", "shell"), true, 2);

        double prob = router.predictCompletion(IntentClassifier.TaskCategory.TESTING, 
            List.of("file_write", "shell"));
        assertEquals(1.0, prob); // 2 complete, 0 incomplete = 100%
    }

    @Test
    void predictCompletionMixedResults() {
        router.recordCompletion(IntentClassifier.TaskCategory.CODING, 
            List.of("file_write"), true, 2);
        router.recordCompletion(IntentClassifier.TaskCategory.CODING, 
            List.of("file_write"), false, 2);
        router.recordCompletion(IntentClassifier.TaskCategory.CODING, 
            List.of("file_write"), false, 3);

        double prob = router.predictCompletion(IntentClassifier.TaskCategory.CODING, List.of("file_write"));
        // 1 complete, 2 incomplete = 33%
        assertTrue(prob > 0.3 && prob < 0.4);
    }

    // ==========================================================================
    // Stall detection tests
    // ==========================================================================

    @Test
    void notStalledWithinAverage() {
        router.recordCompletion(IntentClassifier.TaskCategory.CODING, List.of(), true, 4);
        router.recordCompletion(IntentClassifier.TaskCategory.CODING, List.of(), true, 6);
        // avg = 5, stall = 5 * 2 = 10
        assertFalse(router.isStalled(IntentClassifier.TaskCategory.CODING, 5));
        assertFalse(router.isStalled(IntentClassifier.TaskCategory.CODING, 9));
    }

    @Test
    void stalledBeyondMultiplier() {
        router.recordCompletion(IntentClassifier.TaskCategory.CODING, List.of(), true, 4);
        router.recordCompletion(IntentClassifier.TaskCategory.CODING, List.of(), true, 6);
        // avg = 5, stall = 5 * 2 = 10
        assertTrue(router.isStalled(IntentClassifier.TaskCategory.CODING, 11));
    }

    @Test
    void stalledUsesDefaultWhenNoData() {
        // No training data — default stall threshold is 6
        assertFalse(router.isStalled(IntentClassifier.TaskCategory.DEVOPS, 5));
        assertTrue(router.isStalled(IntentClassifier.TaskCategory.DEVOPS, 7));
    }

    @Test
    void getAvgTurnsReturnsDefault() {
        assertEquals(5, router.getAvgTurns(IntentClassifier.TaskCategory.GIT));
    }

    @Test
    void getAvgTurnsAfterTraining() {
        router.recordCompletion(IntentClassifier.TaskCategory.GIT, List.of(), true, 2);
        router.recordCompletion(IntentClassifier.TaskCategory.GIT, List.of(), true, 4);
        assertEquals(3, router.getAvgTurns(IntentClassifier.TaskCategory.GIT));
    }

    // ==========================================================================
    // recommendAction tests
    // ==========================================================================

    @Test
    void recommendContinueOnNormalState() {
        MarkovRouter.MakerAction action = router.recommendAction(
            IntentClassifier.TaskCategory.CODING, 2, "Coder", true, List.of("file_write"));
        assertEquals(MarkovRouter.MakerAction.CONTINUE, action);
    }

    @Test
    void recommendRetryOnFailure() {
        MarkovRouter.MakerAction action = router.recommendAction(
            IntentClassifier.TaskCategory.CODING, 2, "Tester", false, List.of("shell"));
        assertEquals(MarkovRouter.MakerAction.RETRY, action);
    }

    @Test
    void recommendEscalateOnStall() {
        // Default stall = 6 turns
        MarkovRouter.MakerAction action = router.recommendAction(
            IntentClassifier.TaskCategory.CODING, 8, "Coder", true, List.of());
        assertEquals(MarkovRouter.MakerAction.ESCALATE, action);
    }

    @Test
    void recommendCompleteWhenPatternMatches() {
        router.recordCompletion(IntentClassifier.TaskCategory.TESTING, 
            List.of("shell", "file_write"), true, 2);
        router.recordCompletion(IntentClassifier.TaskCategory.TESTING, 
            List.of("shell", "file_write"), true, 3);
        router.recordCompletion(IntentClassifier.TaskCategory.TESTING, 
            List.of("shell", "file_write"), true, 2);

        MarkovRouter.MakerAction action = router.recommendAction(
            IntentClassifier.TaskCategory.TESTING, 3, "Tester", true, List.of("shell", "file_write"));
        assertEquals(MarkovRouter.MakerAction.COMPLETE, action);
    }
}
