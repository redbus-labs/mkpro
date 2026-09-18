package com.mkpro.maker.distillation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TrajectoryDistillerTest {

    private TrajectoryDistiller distiller;

    @BeforeEach
    public void setUp() {
        distiller = new TrajectoryDistiller();
    }

    @Test
    @DisplayName("Should extract compile error for missing symbol")
    public void testMissingSymbolCompileError() {
        String errorOutput = "[ERROR] /src/main/java/com/example/Service.java:[42,15] cannot find symbol\n" +
                "  symbol:   method calculateChecksum(byte[])\n" +
                "  location: class com.example.Helper";

        ExecutionTrajectory trajectory = ExecutionTrajectory.builder()
                .goalId("goal-comp-1")
                .initialHypothesis("Use Helper.calculateChecksum")
                .step(TrajectoryStep.builder()
                        .stepIndex(1)
                        .agentName("coder")
                        .actionOrTool("compile_project")
                        .outputOrError(errorOutput)
                        .exitCode(1)
                        .isError(true)
                        .build())
                .success(false)
                .build();

        DistilledLesson lesson = distiller.distill(trajectory);

        assertNotNull(lesson);
        assertEquals(FailureCategory.COMPILE_ERROR, lesson.getCategory());
        assertTrue(lesson.getRootCauseSummary().contains("calculateChecksum"));
        assertFalse(lesson.getNegativeConstraints().isEmpty());
        assertTrue(lesson.getNegativeConstraints().get(0).startsWith("Do NOT"));
        assertTrue(lesson.getNegativeConstraints().get(0).contains("calculateChecksum"));
        assertNotNull(lesson.getSuggestedPivot());
    }

    @Test
    @DisplayName("Should extract compile error for type mismatch")
    public void testTypeMismatchCompileError() {
        String errorOutput = "[ERROR] /src/main/java/Service.java:[10,20] incompatible types: java.lang.String cannot be converted to int";

        ExecutionTrajectory trajectory = ExecutionTrajectory.builder()
                .goalId("goal-comp-2")
                .initialHypothesis("Assign string return value to int id")
                .step(TrajectoryStep.builder()
                        .stepIndex(1)
                        .agentName("coder")
                        .actionOrTool("save_component")
                        .outputOrError(errorOutput)
                        .exitCode(1)
                        .isError(true)
                        .build())
                .success(false)
                .build();

        DistilledLesson lesson = distiller.distill(trajectory);

        assertNotNull(lesson);
        assertEquals(FailureCategory.COMPILE_ERROR, lesson.getCategory());
        assertTrue(lesson.getRootCauseSummary().contains("Type mismatch"));
        assertTrue(lesson.getNegativeConstraints().stream().anyMatch(c -> c.contains("incompatible type")));
    }

    @Test
    @DisplayName("Should extract compile error for unhandled exception")
    public void testUnhandledExceptionCompileError() {
        String errorOutput = "[ERROR] Service.java:[15,10] unreported exception java.io.IOException; must be caught or declared to be thrown";

        ExecutionTrajectory trajectory = ExecutionTrajectory.builder()
                .goalId("goal-comp-3")
                .initialHypothesis("Read file directly without throws clause")
                .step(TrajectoryStep.builder()
                        .stepIndex(1)
                        .agentName("coder")
                        .actionOrTool("save_component")
                        .outputOrError(errorOutput)
                        .exitCode(1)
                        .isError(true)
                        .build())
                .success(false)
                .build();

        DistilledLesson lesson = distiller.distill(trajectory);

        assertNotNull(lesson);
        assertEquals(FailureCategory.COMPILE_ERROR, lesson.getCategory());
        assertTrue(lesson.getRootCauseSummary().contains("java.io.IOException"));
        assertTrue(lesson.getSuggestedPivot().contains("try-catch") || lesson.getSuggestedPivot().contains("throws"));
    }

    @Test
    @DisplayName("Should extract test assertion failure with expected vs actual")
    public void testAssertionFailureExtraction() {
        String errorOutput = "org.opentest4j.AssertionFailedError: expected: <42> but was: <0>\n" +
                "\tat org.junit.jupiter.api.AssertionUtils.fail(AssertionUtils.java:55)\n" +
                "\tat com.example.CalculatorTest.testAdd(CalculatorTest.java:23)";

        ExecutionTrajectory trajectory = ExecutionTrajectory.builder()
                .goalId("goal-test-1")
                .initialHypothesis("Stub method returning default 0")
                .step(TrajectoryStep.builder()
                        .stepIndex(1)
                        .agentName("executor")
                        .actionOrTool("run_tests")
                        .outputOrError(errorOutput)
                        .exitCode(1)
                        .isError(true)
                        .build())
                .success(false)
                .build();

        DistilledLesson lesson = distiller.distill(trajectory);

        assertNotNull(lesson);
        assertEquals(FailureCategory.TEST_FAILURE, lesson.getCategory());
        assertTrue(lesson.getRootCauseSummary().contains("expected <42> but was <0>"));
        assertFalse(lesson.getNegativeConstraints().isEmpty());
        assertTrue(lesson.getNegativeConstraints().get(0).startsWith("Do NOT"));
        assertTrue(lesson.getSuggestedPivot().contains("42"));
    }

    @Test
    @DisplayName("Should extract security policy violation")
    public void testSecurityPolicyViolationExtraction() {
        String errorOutput = "CommandPolicy rejected command 'rm -rf /tmp/test' - forbidden recursive deletion outside workspace";

        ExecutionTrajectory trajectory = ExecutionTrajectory.builder()
                .goalId("goal-sec-1")
                .initialHypothesis("Clean workspace using rm -rf")
                .step(TrajectoryStep.builder()
                        .stepIndex(1)
                        .agentName("executor")
                        .actionOrTool("execute_command")
                        .inputPayload("rm -rf /tmp/test")
                        .outputOrError(errorOutput)
                        .exitCode(126)
                        .isError(true)
                        .build())
                .success(false)
                .build();

        DistilledLesson lesson = distiller.distill(trajectory);

        assertNotNull(lesson);
        assertEquals(FailureCategory.SECURITY_POLICY_VIOLATION, lesson.getCategory());
        assertTrue(lesson.getRootCauseSummary().contains("Security policy violation"));
        assertTrue(lesson.getNegativeConstraints().stream().anyMatch(c -> c.contains("restricted")));
    }

    @Test
    @DisplayName("Should extract file not found failure")
    public void testFileNotFoundExtraction() {
        String errorOutput = "java.io.FileNotFoundException: config/application.properties (No such file or directory)";

        ExecutionTrajectory trajectory = ExecutionTrajectory.builder()
                .goalId("goal-fnf-1")
                .initialHypothesis("Load properties file")
                .step(TrajectoryStep.builder()
                        .stepIndex(1)
                        .agentName("reader")
                        .actionOrTool("read_file")
                        .outputOrError(errorOutput)
                        .exitCode(1)
                        .isError(true)
                        .build())
                .success(false)
                .build();

        DistilledLesson lesson = distiller.distill(trajectory);

        assertNotNull(lesson);
        assertEquals(FailureCategory.FILE_NOT_FOUND, lesson.getCategory());
        assertTrue(lesson.getRootCauseSummary().contains("config/application.properties"));
        assertTrue(lesson.getNegativeConstraints().get(0).startsWith("Do NOT"));
    }

    @Test
    @DisplayName("Should extract unexpected runtime exception")
    public void testRuntimeExceptionExtraction() {
        String errorOutput = "java.lang.NullPointerException: Cannot invoke \"String.length()\" because \"str\" is null\n" +
                "\tat com.example.Parser.parse(Parser.java:12)";

        ExecutionTrajectory trajectory = ExecutionTrajectory.builder()
                .goalId("goal-npe-1")
                .initialHypothesis("Direct parse without null checks")
                .step(TrajectoryStep.builder()
                        .stepIndex(1)
                        .agentName("executor")
                        .actionOrTool("run_script")
                        .outputOrError(errorOutput)
                        .exitCode(1)
                        .isError(true)
                        .build())
                .success(false)
                .build();

        DistilledLesson lesson = distiller.distill(trajectory);

        assertNotNull(lesson);
        assertEquals(FailureCategory.UNEXPECTED_EXCEPTION, lesson.getCategory());
        assertTrue(lesson.getRootCauseSummary().contains("NullPointerException"));
        assertTrue(lesson.getSuggestedPivot().contains("null"));
    }

    @Test
    @DisplayName("Should format distilled lesson into clean XML prompt envelope")
    public void testPromptEnvelopeGeneration() {
        DistilledLesson lesson = DistilledLesson.builder()
                .lessonId("lesson-abc-123")
                .category(FailureCategory.COMPILE_ERROR)
                .rootCauseSummary("Cannot find symbol 'processRecord'")
                .failedHypothesis("Assumed processor had method processRecord")
                .negativeConstraint("Do NOT reference 'processRecord' without declaring it")
                .negativeConstraint("Do NOT import wrong processor package")
                .suggestedPivot("Implement processRecord in DataProcessor class")
                .build();

        String xml = lesson.toPromptEnvelope();

        assertNotNull(xml);
        assertTrue(xml.startsWith("<distilled_lesson id=\"lesson-abc-123\">"));
        assertTrue(xml.endsWith("</distilled_lesson>"));
        assertTrue(xml.contains("<category>COMPILE_ERROR</category>"));
        assertTrue(xml.contains("<root_cause>Cannot find symbol &apos;processRecord&apos;</root_cause>"));
        assertTrue(xml.contains("<failed_hypothesis>Assumed processor had method processRecord</failed_hypothesis>"));
        assertTrue(xml.contains("<constraint>Do NOT reference &apos;processRecord&apos; without declaring it</constraint>"));
        assertTrue(xml.contains("<constraint>Do NOT import wrong processor package</constraint>"));
        assertTrue(xml.contains("<suggested_pivot>Implement processRecord in DataProcessor class</suggested_pivot>"));
    }

    @Test
    @DisplayName("Should handle successful trajectory distillation gracefully")
    public void testSuccessfulTrajectoryDistillation() {
        ExecutionTrajectory trajectory = ExecutionTrajectory.builder()
                .goalId("goal-success")
                .initialHypothesis("Valid working solution")
                .step(TrajectoryStep.builder()
                        .stepIndex(1)
                        .agentName("coder")
                        .actionOrTool("save_component")
                        .outputOrError("Saved successfully")
                        .exitCode(0)
                        .isError(false)
                        .build())
                .success(true)
                .build();

        DistilledLesson lesson = distiller.distill(trajectory);

        assertNotNull(lesson);
        assertTrue(lesson.getRootCauseSummary().contains("succeeded"));
    }


    @Test
    @DisplayName("Should detect conversational delay evasion and return AGENT_PROMISE_BREACH when zero tools executed")
    public void testConversationalDelayEvasionDetection() {
        String agentResponse = "Let me explore the project to understand what it's about";
        ExecutionTrajectory trajectory = ExecutionTrajectory.builder()
                .goalId("goal-evasion-1")
                .initialHypothesis("Inspect project structure")
                .success(false)
                .build();

        DistilledLesson lesson = distiller.distill(trajectory, agentResponse);

        assertNotNull(lesson);
        assertEquals(FailureCategory.AGENT_PROMISE_BREACH, lesson.getCategory());
        assertEquals("Agent emitted conversational delay promise without invoking inspection tools.", lesson.getRootCauseSummary());
        assertNotNull(lesson.getNegativeConstraints());
        assertEquals(2, lesson.getNegativeConstraints().size());
        assertTrue(lesson.getNegativeConstraints().contains("Do NOT reply with conversational intent promises like 'Let me check...' or 'Let me read...'."));
        assertTrue(lesson.getNegativeConstraints().contains("You MUST execute tools (file_reader, list_dir, grep) in your very next turn."));
        assertEquals("Invoke file tools immediately to read project metadata or build manifest.", lesson.getSuggestedPivot());
    }

    @Test
    @DisplayName("Should detect evasion for other delay phrases with zero tool calls")
    public void testAdditionalEvasionPhrases() {
        String[] phrases = {
            "Let me check the build file",
            "I will explore the codebase now",
            "I need to read the pom.xml first",
            "Let me inspect the directory structure"
        };

        for (String phrase : phrases) {
            ExecutionTrajectory trajectory = ExecutionTrajectory.builder()
                    .goalId("goal-evasion-multi")
                    .initialHypothesis("Check files")
                    .success(false)
                    .build();

            DistilledLesson lesson = distiller.distill(trajectory, phrase);
            assertNotNull(lesson, "Expected lesson for phrase: " + phrase);
            assertEquals(FailureCategory.AGENT_PROMISE_BREACH, lesson.getCategory(), "Expected AGENT_PROMISE_BREACH for phrase: " + phrase);
        }
    }

    @Test
    @DisplayName("Should not trigger promise breach if tools were actually executed")
    public void testNoEvasionWhenToolsExecuted() {
        String agentResponse = "Let me check the file structure";
        ExecutionTrajectory trajectory = ExecutionTrajectory.builder()
                .goalId("goal-tool-1")
                .initialHypothesis("List files")
                .step(TrajectoryStep.builder()
                        .stepIndex(1)
                        .agentName("reader")
                        .actionOrTool("list_dir")
                        .outputOrError("pom.xml\nsrc")
                        .exitCode(0)
                        .isError(false)
                        .build())
                .success(false)
                .build();

        DistilledLesson lesson = distiller.distill(trajectory, agentResponse);
        assertNotNull(lesson);
        assertNotEquals(FailureCategory.AGENT_PROMISE_BREACH, lesson.getCategory());
    }
}
