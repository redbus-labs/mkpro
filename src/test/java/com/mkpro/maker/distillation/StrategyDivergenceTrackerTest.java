package com.mkpro.maker.distillation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class StrategyDivergenceTrackerTest {

    private StrategyDivergenceTracker tracker;

    @BeforeEach
    public void setUp() {
        tracker = new StrategyDivergenceTracker();
    }

    @Test
    @DisplayName("Identical trajectories should have similarity 1.0 and isStrategyLooping true")
    public void testIdenticalTrajectories() {
        ExecutionTrajectory t1 = ExecutionTrajectory.builder()
                .goalId("goal-1")
                .step(TrajectoryStep.builder().stepIndex(1).agentName("coder").actionOrTool("search_code").build())
                .step(TrajectoryStep.builder().stepIndex(2).agentName("coder").actionOrTool("save_component").build())
                .step(TrajectoryStep.builder().stepIndex(3).agentName("executor").actionOrTool("run_tests").build())
                .build();

        ExecutionTrajectory t2 = ExecutionTrajectory.builder()
                .goalId("goal-1")
                .step(TrajectoryStep.builder().stepIndex(1).agentName("coder").actionOrTool("search_code").build())
                .step(TrajectoryStep.builder().stepIndex(2).agentName("coder").actionOrTool("save_component").build())
                .step(TrajectoryStep.builder().stepIndex(3).agentName("executor").actionOrTool("run_tests").build())
                .build();

        double similarity = tracker.calculateSimilarity(t1, t2);
        assertEquals(1.0, similarity, 0.001);
        assertTrue(tracker.isStrategyLooping(t1, t2));
    }

    @Test
    @DisplayName("Completely divergent trajectories should have similarity 0.0 and isStrategyLooping false")
    public void testCompletelyDivergentTrajectories() {
        ExecutionTrajectory t1 = ExecutionTrajectory.builder()
                .goalId("goal-1")
                .step(TrajectoryStep.builder().stepIndex(1).agentName("reader").actionOrTool("read_file").build())
                .step(TrajectoryStep.builder().stepIndex(2).agentName("reader").actionOrTool("read_clipboard").build())
                .build();

        ExecutionTrajectory t2 = ExecutionTrajectory.builder()
                .goalId("goal-1")
                .step(TrajectoryStep.builder().stepIndex(1).agentName("executor").actionOrTool("execute_command").build())
                .step(TrajectoryStep.builder().stepIndex(2).agentName("network").actionOrTool("fetch_url").build())
                .build();

        double similarity = tracker.calculateSimilarity(t1, t2);
        assertEquals(0.0, similarity, 0.001);
        assertFalse(tracker.isStrategyLooping(t1, t2));
    }

    @Test
    @DisplayName("Partially divergent trajectories should score between 0.0 and 1.0")
    public void testPartiallyDivergentTrajectories() {
        ExecutionTrajectory t1 = ExecutionTrajectory.builder()
                .goalId("goal-1")
                .step(TrajectoryStep.builder().stepIndex(1).agentName("coder").actionOrTool("search_code").build())
                .step(TrajectoryStep.builder().stepIndex(2).agentName("coder").actionOrTool("save_component").build())
                .step(TrajectoryStep.builder().stepIndex(3).agentName("executor").actionOrTool("run_tests").build())
                .build();

        ExecutionTrajectory t2 = ExecutionTrajectory.builder()
                .goalId("goal-1")
                .step(TrajectoryStep.builder().stepIndex(1).agentName("coder").actionOrTool("search_code").build())
                .step(TrajectoryStep.builder().stepIndex(2).agentName("coder").actionOrTool("save_component").build())
                .step(TrajectoryStep.builder().stepIndex(3).agentName("debugger").actionOrTool("analyze_logs").build())
                .step(TrajectoryStep.builder().stepIndex(4).agentName("executor").actionOrTool("execute_script").build())
                .build();

        double similarity = tracker.calculateSimilarity(t1, t2);
        assertTrue(similarity > 0.0 && similarity < 1.0, "Similarity should be intermediate: " + similarity);

        // With strict 0.9 threshold it shouldn't loop
        assertFalse(tracker.isStrategyLooping(t1, t2, 0.90));
    }

    @Test
    @DisplayName("Handles empty and null trajectories gracefully")
    public void testEdgeCases() {
        ExecutionTrajectory empty1 = new ExecutionTrajectory("g1", "h1");
        ExecutionTrajectory empty2 = new ExecutionTrajectory("g1", "h2");

        // Both empty trajectories
        assertEquals(1.0, tracker.calculateSimilarity(empty1, empty2), 0.001);

        // One empty, one populated
        ExecutionTrajectory populated = ExecutionTrajectory.builder()
                .goalId("g1")
                .step(TrajectoryStep.builder().stepIndex(1).agentName("a").actionOrTool("t").build())
                .build();

        assertEquals(0.0, tracker.calculateSimilarity(empty1, populated), 0.001);
        assertFalse(tracker.isStrategyLooping(empty1, populated));

        // Null handling
        assertEquals(1.0, tracker.calculateSimilarity(null, null), 0.001);
        assertEquals(0.0, tracker.calculateSimilarity(populated, null), 0.001);
        assertEquals(0.0, tracker.calculateSimilarity(null, populated), 0.001);
    }

    @Test
    @DisplayName("Custom looping threshold works correctly")
    public void testCustomThreshold() {
        ExecutionTrajectory t1 = ExecutionTrajectory.builder()
                .step(TrajectoryStep.builder().agentName("a").actionOrTool("tool1").build())
                .step(TrajectoryStep.builder().agentName("a").actionOrTool("tool2").build())
                .build();

        ExecutionTrajectory t2 = ExecutionTrajectory.builder()
                .step(TrajectoryStep.builder().agentName("a").actionOrTool("tool1").build())
                .step(TrajectoryStep.builder().agentName("a").actionOrTool("tool3").build())
                .build();

        double sim = tracker.calculateSimilarity(t1, t2);
        assertTrue(sim > 0.0 && sim < 1.0);

        assertTrue(tracker.isStrategyLooping(t1, t2, sim - 0.05));
        assertFalse(tracker.isStrategyLooping(t1, t2, sim + 0.05));
    }
}
