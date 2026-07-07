package com.mkpro;

import com.mkpro.models.Goal;
import org.junit.jupiter.api.*;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MakerGoalStimulusTest {

    private CentralMemory memory;
    private static final String PROJECT = "/test/project";
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = java.nio.file.Files.createTempDirectory("maker-goal-test-");
        Path sharedDb = tempDir.resolve("shared").resolve("cm.db");
        Path localDb = tempDir.resolve("local").resolve("stats.db");
        memory = new CentralMemory(sharedDb, localDb);
    }

    @AfterEach
    void tearDown() {
        if (memory != null) { memory.close(); memory = null; }
        try {
            java.nio.file.Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }

    // --- Null/empty cases ---

    @Test
    void nullMemoryReturnsError() {
        String result = Maker.getGoalStimulus(null, PROJECT);
        assertEquals("Error: Memory or Project Path is null.", result);
    }

    @Test
    void nullProjectPathReturnsError() {
        String result = Maker.getGoalStimulus(memory, null);
        assertEquals("Error: Memory or Project Path is null.", result);
    }

    @Test
    void noGoalsReturnsNoGoalsMessage() {
        String result = Maker.getGoalStimulus(memory, PROJECT);
        assertEquals("No goals defined for this project.", result);
    }

    @Test
    void allCompletedGoalsReturnsCompletedMessage() {
        Goal g1 = new Goal("Task 1");
        g1.setStatus(Goal.Status.COMPLETED);
        Goal g2 = new Goal("Task 2");
        g2.setStatus(Goal.Status.COMPLETED);
        memory.setGoals(PROJECT, List.of(g1, g2));

        String result = Maker.getGoalStimulus(memory, PROJECT);
        assertEquals("All goals are currently marked as COMPLETED.", result);
    }

    // --- Priority ordering: FAILED > IN_PROGRESS > PENDING ---

    @Test
    void failedGoalsAppearFirst() {
        Goal pending = new Goal("Pending task");
        Goal failed = new Goal("Failed task");
        failed.setStatus(Goal.Status.FAILED);
        Goal inProgress = new Goal("In progress task");
        inProgress.setStatus(Goal.Status.IN_PROGRESS);

        memory.setGoals(PROJECT, List.of(pending, failed, inProgress));

        String result = Maker.getGoalStimulus(memory, PROJECT);
        int failedIdx = result.indexOf("[CRITICAL ATTENTION REQUIRED - FAILED]");
        int inProgressIdx = result.indexOf("[CURRENT FOCUS - IN PROGRESS]");
        int pendingIdx = result.indexOf("[UPCOMING TASKS - PENDING]");

        assertTrue(failedIdx >= 0, "Should contain FAILED section");
        assertTrue(inProgressIdx >= 0, "Should contain IN_PROGRESS section");
        assertTrue(pendingIdx >= 0, "Should contain PENDING section");
        assertTrue(failedIdx < inProgressIdx, "FAILED should appear before IN_PROGRESS");
        assertTrue(inProgressIdx < pendingIdx, "IN_PROGRESS should appear before PENDING");
    }

    @Test
    void failedGoalMarkedWithExclamation() {
        Goal failed = new Goal("Fix critical bug");
        failed.setStatus(Goal.Status.FAILED);
        memory.setGoals(PROJECT, List.of(failed));

        String result = Maker.getGoalStimulus(memory, PROJECT);
        assertTrue(result.contains("! Fix critical bug"));
    }

    @Test
    void inProgressGoalMarkedWithGreaterThan() {
        Goal ip = new Goal("Implement feature");
        ip.setStatus(Goal.Status.IN_PROGRESS);
        memory.setGoals(PROJECT, List.of(ip));

        String result = Maker.getGoalStimulus(memory, PROJECT);
        assertTrue(result.contains("> Implement feature"));
    }

    @Test
    void pendingGoalMarkedWithDash() {
        Goal pending = new Goal("Write tests");
        memory.setGoals(PROJECT, List.of(pending));

        String result = Maker.getGoalStimulus(memory, PROJECT);
        assertTrue(result.contains("- Write tests"));
    }

    // --- 5-item cap on pending ---

    @Test
    void pendingCappedAtFiveItems() {
        Goal g1 = new Goal("Task 1");
        Goal g2 = new Goal("Task 2");
        Goal g3 = new Goal("Task 3");
        Goal g4 = new Goal("Task 4");
        Goal g5 = new Goal("Task 5");
        Goal g6 = new Goal("Task 6");
        Goal g7 = new Goal("Task 7");

        memory.setGoals(PROJECT, List.of(g1, g2, g3, g4, g5, g6, g7));

        String result = Maker.getGoalStimulus(memory, PROJECT);
        assertTrue(result.contains("... (and more)"), "Should show truncation indicator");
        // Count dash-prefixed lines in PENDING section
        String pendingSection = result.substring(result.indexOf("[UPCOMING TASKS - PENDING]"));
        long dashLines = pendingSection.lines().filter(l -> l.startsWith("- ")).count();
        assertEquals(5, dashLines, "Should show exactly 5 pending items");
    }

    // --- Effective leaf extraction ---

    @Test
    void effectiveLeafWithNoSubGoals() {
        Goal leaf = new Goal("Simple task");
        leaf.setStatus(Goal.Status.IN_PROGRESS);
        memory.setGoals(PROJECT, List.of(leaf));

        String result = Maker.getGoalStimulus(memory, PROJECT);
        assertTrue(result.contains("> Simple task"));
    }

    @Test
    void parentWithAllCompletedChildrenIsEffectiveLeaf() {
        Goal parent = new Goal("Parent task");
        parent.setStatus(Goal.Status.IN_PROGRESS);
        Goal child1 = new Goal("Child 1");
        child1.setStatus(Goal.Status.COMPLETED);
        Goal child2 = new Goal("Child 2");
        child2.setStatus(Goal.Status.COMPLETED);
        parent.addSubGoal(child1);
        parent.addSubGoal(child2);

        memory.setGoals(PROJECT, List.of(parent));

        String result = Maker.getGoalStimulus(memory, PROJECT);
        // Parent becomes effective leaf since all children are done
        assertTrue(result.contains("> Parent task"));
    }

    @Test
    void parentWithIncompleteChildrenShowsChildren() {
        Goal parent = new Goal("Parent");
        parent.setStatus(Goal.Status.IN_PROGRESS);
        Goal child1 = new Goal("Child pending");
        // child1 is PENDING by default
        Goal child2 = new Goal("Child done");
        child2.setStatus(Goal.Status.COMPLETED);
        parent.addSubGoal(child1);
        parent.addSubGoal(child2);

        memory.setGoals(PROJECT, List.of(parent));

        String result = Maker.getGoalStimulus(memory, PROJECT);
        // Should show the incomplete child with parent path
        assertTrue(result.contains("Parent > Child pending"),
            "Should show path: Parent > Child pending. Got: " + result);
    }

    // --- Report header ---

    @Test
    void reportStartsWithHeader() {
        Goal g = new Goal("Task");
        memory.setGoals(PROJECT, List.of(g));

        String result = Maker.getGoalStimulus(memory, PROJECT);
        assertTrue(result.startsWith("GOAL STIMULUS REPORT:\n"));
    }

    // --- areGoalsPending ---

    @Test
    void areGoalsPendingReturnsFalseForNull() {
        assertFalse(Maker.areGoalsPending(null, PROJECT));
        assertFalse(Maker.areGoalsPending(memory, null));
    }

    @Test
    void areGoalsPendingReturnsFalseWhenAllCompleted() {
        Goal g = new Goal("Done");
        g.setStatus(Goal.Status.COMPLETED);
        memory.setGoals(PROJECT, List.of(g));
        assertFalse(Maker.areGoalsPending(memory, PROJECT));
    }

    @Test
    void areGoalsPendingReturnsTrueWhenPending() {
        memory.setGoals(PROJECT, List.of(new Goal("Pending")));
        assertTrue(Maker.areGoalsPending(memory, PROJECT));
    }
}
