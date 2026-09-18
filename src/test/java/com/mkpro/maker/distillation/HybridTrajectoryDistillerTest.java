package com.mkpro.maker.distillation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class HybridTrajectoryDistillerTest {

    @Test
    @DisplayName("Should use rule-based distillation when LLM provider is null")
    public void testRuleBasedFallbackWhenNoLlm() {
        HybridTrajectoryDistiller distiller = new HybridTrajectoryDistiller();

        String compileError = "[ERROR] /src/main/java/Service.java:[10,5] cannot find symbol\n" +
                "  symbol:   method executeQuery(String)\n" +
                "  location: class com.mkpro.db.Database";

        ExecutionTrajectory trajectory = ExecutionTrajectory.builder()
                .goalId("test-goal-1")
                .initialHypothesis("Call Database.executeQuery directly")
                .step(TrajectoryStep.builder()
                        .stepIndex(1)
                        .agentName("coder")
                        .actionOrTool("save_component")
                        .outputOrError(compileError)
                        .exitCode(1)
                        .isError(true)
                        .build())
                .success(false)
                .build();

        DistilledLesson lesson = distiller.distill(trajectory);

        assertNotNull(lesson);
        assertEquals(FailureCategory.COMPILE_ERROR, lesson.getCategory());
        assertTrue(lesson.getRootCauseSummary().contains("executeQuery"));
        assertFalse(lesson.getNegativeConstraints().isEmpty());
    }

    @Test
    @DisplayName("Should enrich lesson using LLM provider response when available")
    public void testLlmEnrichmentSuccess() {
        String llmXml = "<distilled_lesson>\n" +
                "  <category>UNEXPECTED_EXCEPTION</category>\n" +
                "  <root_cause>Null pointer when accessing uninitialized config map</root_cause>\n" +
                "  <failed_hypothesis>Assumed configuration map is populated during bootstrap</failed_hypothesis>\n" +
                "  <negative_constraints>\n" +
                "    <constraint>Do NOT access config without null check</constraint>\n" +
                "    <constraint>Do NOT bypass bootstrap initialization</constraint>\n" +
                "  </negative_constraints>\n" +
                "  <suggested_pivot>Initialize config defaults before accessing</suggested_pivot>\n" +
                "</distilled_lesson>";

        AtomicBoolean llmCalled = new AtomicBoolean(false);
        HybridTrajectoryDistiller distiller = new HybridTrajectoryDistiller(prompt -> {
            llmCalled.set(true);
            assertTrue(prompt.contains("test-goal-2"));
            return llmXml;
        });

        ExecutionTrajectory trajectory = ExecutionTrajectory.builder()
                .goalId("test-goal-2")
                .initialHypothesis("Read config")
                .step(TrajectoryStep.builder()
                        .stepIndex(1)
                        .agentName("executor")
                        .actionOrTool("run_service")
                        .outputOrError("java.lang.NullPointerException at com.mkpro.Config.get(Config.java:12)")
                        .exitCode(1)
                        .isError(true)
                        .build())
                .success(false)
                .build();

        DistilledLesson lesson = distiller.distill(trajectory);

        assertTrue(llmCalled.get());
        assertNotNull(lesson);
        assertEquals(FailureCategory.UNEXPECTED_EXCEPTION, lesson.getCategory());
        assertEquals("Null pointer when accessing uninitialized config map", lesson.getRootCauseSummary());
        assertEquals("Assumed configuration map is populated during bootstrap", lesson.getFailedHypothesis());
        assertEquals(2, lesson.getNegativeConstraints().size());
        assertTrue(lesson.getNegativeConstraints().contains("Do NOT access config without null check"));
        assertEquals("Initialize config defaults before accessing", lesson.getSuggestedPivot());
    }

    @Test
    @DisplayName("Should gracefully fallback to rule-based lesson when LLM provider throws an exception")
    public void testGracefulFallbackOnLlmException() {
        HybridTrajectoryDistiller distiller = new HybridTrajectoryDistiller(prompt -> {
            throw new RuntimeException("LLM API connection timeout");
        });

        String error = "CommandPolicy rejected command 'sudo reboot' - forbidden command";
        ExecutionTrajectory trajectory = ExecutionTrajectory.builder()
                .goalId("test-goal-3")
                .initialHypothesis("Reboot system to apply updates")
                .step(TrajectoryStep.builder()
                        .stepIndex(1)
                        .agentName("executor")
                        .actionOrTool("execute_command")
                        .outputOrError(error)
                        .exitCode(1)
                        .isError(true)
                        .build())
                .success(false)
                .build();

        DistilledLesson lesson = assertDoesNotThrow(() -> distiller.distill(trajectory));

        assertNotNull(lesson);
        assertEquals(FailureCategory.SECURITY_POLICY_VIOLATION, lesson.getCategory());
        assertTrue(lesson.getRootCauseSummary().contains("Security policy violation") || lesson.getRootCauseSummary().contains("CommandPolicy rejected"));
    }

    @Test
    @DisplayName("Should fallback to rule-based lesson when LLM returns non-XML or empty response")
    public void testFallbackOnInvalidLlmResponse() {
        HybridTrajectoryDistiller distiller = new HybridTrajectoryDistiller(prompt -> "Just a generic response without XML tags");

        ExecutionTrajectory trajectory = ExecutionTrajectory.builder()
                .goalId("test-goal-4")
                .initialHypothesis("Run tests")
                .step(TrajectoryStep.builder()
                        .stepIndex(1)
                        .agentName("executor")
                        .actionOrTool("run_tests")
                        .outputOrError("org.opentest4j.AssertionFailedError: expected: <100> but was: <0>")
                        .exitCode(1)
                        .isError(true)
                        .build())
                .success(false)
                .build();

        DistilledLesson lesson = distiller.distill(trajectory);

        assertNotNull(lesson);
        assertEquals(FailureCategory.TEST_FAILURE, lesson.getCategory());
        assertTrue(lesson.getRootCauseSummary().contains("expected <100> but was <0>"));
    }

    @Test
    @DisplayName("distill(ExecutionTrajectory, String) overload should properly set last error and distill")
    public void testDistillWithResponseOverload() {
        HybridTrajectoryDistiller distiller = new HybridTrajectoryDistiller();

        ExecutionTrajectory trajectory = new ExecutionTrajectory("goal-overload", "Hypothesis");
        DistilledLesson lesson = distiller.distill(trajectory, "java.io.FileNotFoundException: /data/config.json (No such file or directory)");

        assertNotNull(lesson);
        assertEquals(FailureCategory.FILE_NOT_FOUND, lesson.getCategory());
        assertTrue(lesson.getRootCauseSummary().contains("/data/config.json"));
    }
}
