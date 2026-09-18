package com.mkpro.maker.distillation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trajectory distillation engine that parses execution trajectories, extracts failure patterns
 * via deterministic regex and heuristic extractors, and synthesizes distilled lessons with
 * root causes, negative constraints, and actionable pivot suggestions.
 */
public class TrajectoryDistiller {

    // Conversational evasion phrases that indicate an unfulfilled conversational promise when 0 tools are run
    private static final String[] CONVERSATIONAL_EVASION_PHRASES = {
        "let me check",
        "let me look",
        "let me explore",
        "let me try reading",
        "let me inspect",
        "i will explore",
        "i will look at",
        "i need to read"
    };


    // Regex patterns for classification and extraction
    private static final Pattern SECURITY_VIOLATION_PATTERN = Pattern.compile(
            "(?i)(?:CommandPolicy rejected|Security policy violation|command is blocked|forbidden command|operation not permitted|access denied|permission denied|security exception|blocked by security policy)(?::?\\s*([^\\r\\n]+))?"
    );

    private static final Pattern CANNOT_FIND_SYMBOL_PATTERN = Pattern.compile(
            "(?i)cannot find symbol[\\s\\S]*?symbol:\\s*(class|method|variable|constructor)?\\s*([a-zA-Z0-9_$.]+)"
    );

    private static final Pattern TYPE_MISMATCH_PATTERN = Pattern.compile(
            "(?i)incompatible types:\\s*(?:found\\s+([^\\n\\r,;]+)\\s+required\\s+([^\\n\\r,;]+)|([^\\n\\r,;]+)\\s+cannot be converted to\\s+([^\\n\\r,;]+))"
    );

    private static final Pattern UNHANDLED_EXCEPTION_PATTERN = Pattern.compile(
            "(?i)unreported exception\\s+([a-zA-Z0-9_$.]+);\\s*must be caught or declared to be thrown"
    );

    private static final Pattern PACKAGE_NOT_EXIST_PATTERN = Pattern.compile(
            "(?i)package\\s+([a-zA-Z0-9_$.]+)\\s+does not exist"
    );

    private static final Pattern COMPILER_GENERIC_PATTERN = Pattern.compile(
            "(?i)(?:compilation (?:error|failure)|\\[ERROR\\]\\s+[^\\r\\n]+\\.java:\\[\\d+,\\d+\\]|javac error|Compilation failed)"
    );

    private static final Pattern TEST_ASSERTION_EXPECTED_ACTUAL = Pattern.compile(
            "(?i)(?:AssertionError|AssertionFailedError|ComparisonFailure):\\s*(?:expected:\\s*<([^>]*)>\\s*but was:\\s*<([^>]*)>|expected:<([^>]*)>\\s*but was:<([^>]*)>|expected\\s*\\[([^\\]]*)\\]\\s*but found\\s*\\[([^\\]]*)\\]|expected:\\s*(.+?)\\s+but was:\\s*(.+))"
    );

    private static final Pattern TEST_ASSERTION_GENERIC = Pattern.compile(
            "(?i)(?:AssertionError|AssertionFailedError|ComparisonFailure|org\\.opentest4j\\.AssertionFailedError)(?::?\\s*([^\\r\\n]+))?"
    );

    private static final Pattern TEST_FAILURES_SUMMARY = Pattern.compile(
            "(?i)Tests run:\\s*\\d+,\\s*Failures:\\s*([1-9]\\d*)|Failures:\\s*([1-9]\\d*)"
    );

    private static final Pattern FILE_NOT_FOUND_PATTERN = Pattern.compile(
            "(?i)(?:FileNotFoundException|NoSuchFileException|No such file or directory|Cannot find file|File does not exist)(?::\\s*([^\\r\\n]+))?"
    );

    private static final Pattern SYNTAX_ERROR_PATTERN = Pattern.compile(
            "(?i)(?:syntax error|illegal start of expression|';' expected|'\\)' expected|'\\}' expected|<identifier> expected|reached end of file while parsing|class, interface, enum, or record expected)"
    );

    private static final Pattern TOOL_PARAMETER_PATTERN = Pattern.compile(
            "(?i)(?:missing required parameter|invalid parameter|parameter '[^']+' is required|unknown tool parameter|invalid argument for tool)(?::?\\s*([^\\r\\n]+))?"
    );

    private static final Pattern TIMEOUT_PATTERN = Pattern.compile(
            "(?i)(?:TimeoutException|timed out after|execution timed out|process stalled|deadline exceeded|command timed out)"
    );

    private static final Pattern RUNTIME_EXCEPTION_PATTERN = Pattern.compile(
            "(?i)(NullPointerException|ClassCastException|IllegalArgumentException|IllegalStateException|IndexOutOfBoundsException|ArrayIndexOutOfBoundsException|StackOverflowError|OutOfMemoryError|UnsupportedOperationException|NumberFormatException)(?::?\\s*([^\\r\\n]+))?"
    );

    /**
     * Evaluates agent promise fidelity and intercepts conversational delay evasion.
     * If conversational evasion phrases are detected without tool invocations or file reads,
     * returns a DistilledLesson with AGENT_PROMISE_BREACH category.
     *
     * @param trajectory The execution trajectory
     * @param response The agent response text
     * @return DistilledLesson if breach detected, null otherwise
     */
    public DistilledLesson evaluateAgentPromiseFidelity(ExecutionTrajectory trajectory, String response) {
        if (response == null || response.isBlank()) {
            if (trajectory != null && trajectory.getLastError() != null) {
                response = trajectory.getLastError();
            } else {
                return null;
            }
        }

        String lower = response.toLowerCase();
        boolean hasEvasionPhrase = false;
        for (String phrase : CONVERSATIONAL_EVASION_PHRASES) {
            if (lower.contains(phrase)) {
                hasEvasionPhrase = true;
                break;
            }
        }

        if (hasEvasionPhrase && hasZeroToolExecutions(trajectory)) {
            String lessonId = "lesson-" + UUID.randomUUID().toString().substring(0, 8);
            String failedHypothesis = (trajectory != null && trajectory.getInitialHypothesis() != null)
                    ? trajectory.getInitialHypothesis()
                    : "Assumed conversational reply without tool invocation would make progress.";

            List<String> negativeConstraints = new ArrayList<>();
            negativeConstraints.add("Do NOT reply with conversational intent promises like 'Let me check...' or 'Let me read...'.");
            negativeConstraints.add("You MUST execute tools (file_reader, list_dir, grep) in your very next turn.");

            return DistilledLesson.builder()
                    .lessonId(lessonId)
                    .category(FailureCategory.AGENT_PROMISE_BREACH)
                    .rootCauseSummary("Agent emitted conversational delay promise without invoking inspection tools.")
                    .failedHypothesis(failedHypothesis)
                    .negativeConstraints(negativeConstraints)
                    .suggestedPivot("Invoke file tools immediately to read project metadata or build manifest.")
                    .build();
        }

        return null;
    }

    public DistilledLesson evaluateAgentPromiseFidelity(ExecutionTrajectory trajectory) {
        return evaluateAgentPromiseFidelity(trajectory, trajectory != null ? trajectory.getLastError() : null);
    }

    private boolean hasZeroToolExecutions(ExecutionTrajectory trajectory) {
        if (trajectory == null || trajectory.getSteps() == null || trajectory.getSteps().isEmpty()) {
            return true;
        }
        return trajectory.getSteps().stream().allMatch(step ->
                step.getActionOrTool() == null ||
                step.getActionOrTool().isBlank() ||
                step.getActionOrTool().equalsIgnoreCase("none") ||
                step.getActionOrTool().equalsIgnoreCase("unknown_action")
        );
    }

    public DistilledLesson distill(ExecutionTrajectory trajectory, String response) {
        DistilledLesson promiseBreach = evaluateAgentPromiseFidelity(trajectory, response);
        if (promiseBreach != null) {
            return promiseBreach;
        }

        if (trajectory == null) {
            trajectory = new ExecutionTrajectory("turn", "Turn execution");
            trajectory.setSuccess(false);
        }
        if (response != null && !response.isBlank()) {
            trajectory.setLastError(response);
        }
        return distill(trajectory);
    }

    public DistilledLesson distill(ExecutionTrajectory trajectory) {
        if (trajectory == null) {
            return null;
        }

        DistilledLesson promiseBreach = evaluateAgentPromiseFidelity(trajectory, trajectory.getLastError());
        if (promiseBreach != null) {
            return promiseBreach;
        }

        String lessonId = "lesson-" + UUID.randomUUID().toString().substring(0, 8);
        String failedHypothesis = trajectory.getInitialHypothesis() != null
                ? trajectory.getInitialHypothesis()
                : "Assumed trajectory actions would achieve goal " + (trajectory.getGoalId() != null ? trajectory.getGoalId() : "");

        if (trajectory.isSuccess() && trajectory.getFailedSteps().isEmpty()) {
            return DistilledLesson.builder()
                    .lessonId(lessonId)
                    .category(FailureCategory.UNKNOWN)
                    .rootCauseSummary("Trajectory succeeded with no observed errors.")
                    .failedHypothesis(failedHypothesis)
                    .suggestedPivot("Maintain current successful approach.")
                    .build();
        }

        // Aggregate error text from failed steps or last error
        List<TrajectoryStep> failedSteps = trajectory.getFailedSteps();
        StringBuilder aggregatedErrors = new StringBuilder();
        String lastTool = "unknown_action";
        String lastPayload = "";

        if (!failedSteps.isEmpty()) {
            for (TrajectoryStep fs : failedSteps) {
                if (fs.getOutputOrError() != null) {
                    aggregatedErrors.append(fs.getOutputOrError()).append("\n");
                }
                if (fs.getActionOrTool() != null) {
                    lastTool = fs.getActionOrTool();
                }
                if (fs.getInputPayload() != null) {
                    lastPayload = fs.getInputPayload();
                }
            }
        } else if (trajectory.getLastError() != null) {
            aggregatedErrors.append(trajectory.getLastError());
        }

        String rawError = aggregatedErrors.toString().trim();
        if (rawError.isEmpty() && !trajectory.isSuccess()) {
            rawError = "Trajectory failed without explicit error stream output (exit code failure).";
        }

        // Distillation heuristics in order of specificity
        FailureAnalysis analysis = analyzeFailure(rawError, lastTool, lastPayload);

        List<String> negativeConstraints = new ArrayList<>(analysis.negativeConstraints);
        if (negativeConstraints.isEmpty()) {
            negativeConstraints.add("Do NOT repeat the failed action sequence with identical input parameters.");
        }

        return DistilledLesson.builder()
                .lessonId(lessonId)
                .category(analysis.category)
                .rootCauseSummary(analysis.rootCause)
                .failedHypothesis(failedHypothesis)
                .negativeConstraints(negativeConstraints)
                .suggestedPivot(analysis.suggestedPivot)
                .build();
    }

    private FailureAnalysis analyzeFailure(String errorText, String tool, String payload) {
        // 1. Security Policy Violation
        Matcher secMatcher = SECURITY_VIOLATION_PATTERN.matcher(errorText);
        if (secMatcher.find()) {
            String details = secMatcher.group(1);
            String target = (details != null && !details.trim().isEmpty()) ? details.trim() : payload;
            String rootCause = "Security policy violation: Command or action blocked by security rules: " + (target.isEmpty() ? "restricted operation" : target);
            List<String> constraints = new ArrayList<>();
            constraints.add("Do NOT attempt executing restricted command or unsafe operation: " + (target.isEmpty() ? tool : target));
            constraints.add("Do NOT bypass security boundaries using shell chaining or non-allowlisted binaries.");
            String pivot = "Use allowed standard APIs, native SDKs, or approved tool capabilities instead of restricted shell commands.";
            return new FailureAnalysis(FailureCategory.SECURITY_POLICY_VIOLATION, rootCause, constraints, pivot);
        }

        // 2. Compiler Errors: Missing Symbol
        Matcher symMatcher = CANNOT_FIND_SYMBOL_PATTERN.matcher(errorText);
        if (symMatcher.find()) {
            String kind = symMatcher.group(1) != null ? symMatcher.group(1) : "symbol";
            String name = symMatcher.group(2) != null ? symMatcher.group(2) : "unknown";
            String rootCause = "Compiler error: Cannot find " + kind + " '" + name + "'.";
            List<String> constraints = new ArrayList<>();
            constraints.add("Do NOT reference " + kind + " '" + name + "' without defining, importing, or adding required dependency.");
            String pivot = "Verify package imports, check method/class signatures, or declare missing " + kind + " '" + name + "' before invocation.";
            return new FailureAnalysis(FailureCategory.COMPILE_ERROR, rootCause, constraints, pivot);
        }

        // 2b. Compiler Errors: Type Mismatch
        Matcher typeMatcher = TYPE_MISMATCH_PATTERN.matcher(errorText);
        if (typeMatcher.find()) {
            String found = typeMatcher.group(1) != null ? typeMatcher.group(1) : typeMatcher.group(3);
            String required = typeMatcher.group(2) != null ? typeMatcher.group(2) : typeMatcher.group(4);
            String rootCause = "Compiler error: Type mismatch - required '" + required + "' but found '" + found + "'.";
            List<String> constraints = new ArrayList<>();
            constraints.add("Do NOT pass or assign incompatible type '" + found + "' where '" + required + "' is expected.");
            String pivot = "Convert, adapt, or cast type '" + found + "' to required type '" + required + "'.";
            return new FailureAnalysis(FailureCategory.COMPILE_ERROR, rootCause, constraints, pivot);
        }

        // 2c. Compiler Errors: Unhandled Exception
        Matcher excMatcher = UNHANDLED_EXCEPTION_PATTERN.matcher(errorText);
        if (excMatcher.find()) {
            String excType = excMatcher.group(1);
            String rootCause = "Compiler error: Unreported checked exception '" + excType + "' must be caught or declared.";
            List<String> constraints = new ArrayList<>();
            constraints.add("Do NOT leave unchecked invocation throwing '" + excType + "' without try-catch or throws clause.");
            String pivot = "Wrap invocation throwing '" + excType + "' in a try-catch block or declare throws in method signature.";
            return new FailureAnalysis(FailureCategory.COMPILE_ERROR, rootCause, constraints, pivot);
        }

        // 2d. Compiler Errors: Package Does Not Exist
        Matcher pkgMatcher = PACKAGE_NOT_EXIST_PATTERN.matcher(errorText);
        if (pkgMatcher.find()) {
            String pkgName = pkgMatcher.group(1);
            String rootCause = "Compiler error: Package '" + pkgName + "' does not exist.";
            List<String> constraints = new ArrayList<>();
            constraints.add("Do NOT import non-existent package '" + pkgName + "'.");
            String pivot = "Check artifact dependencies in pom.xml / build config or verify correct package name for '" + pkgName + "'.";
            return new FailureAnalysis(FailureCategory.COMPILE_ERROR, rootCause, constraints, pivot);
        }

        // 2e. Compiler Errors: Syntax Error
        Matcher synMatcher = SYNTAX_ERROR_PATTERN.matcher(errorText);
        if (synMatcher.find()) {
            String rootCause = "Compiler syntax error: Invalid Java syntax detected (" + synMatcher.group(0) + ").";
            List<String> constraints = new ArrayList<>();
            constraints.add("Do NOT leave unclosed blocks, misplaced semicolons, or invalid token structures in source code.");
            String pivot = "Review code around the reported line number and fix syntax punctuation, braces, or keywords.";
            return new FailureAnalysis(FailureCategory.SYNTAX_ERROR, rootCause, constraints, pivot);
        }

        // 2f. Generic Compiler Errors
        Matcher compGeneric = COMPILER_GENERIC_PATTERN.matcher(errorText);
        if (compGeneric.find()) {
            String rootCause = "Compilation failure detected in project source.";
            List<String> constraints = new ArrayList<>();
            constraints.add("Do NOT continue with broken build state without fixing compilation errors.");
            String pivot = "Examine compiler diagnostics, inspect failed lines, and resolve code defects.";
            return new FailureAnalysis(FailureCategory.COMPILE_ERROR, rootCause, constraints, pivot);
        }

        // 3. Test Assertion Failure
        Matcher testMatcher = TEST_ASSERTION_EXPECTED_ACTUAL.matcher(errorText);
        if (testMatcher.find()) {
            String expected = testMatcher.group(1) != null ? testMatcher.group(1)
                    : (testMatcher.group(3) != null ? testMatcher.group(3)
                    : (testMatcher.group(5) != null ? testMatcher.group(5) : testMatcher.group(7)));
            String actual = testMatcher.group(2) != null ? testMatcher.group(2)
                    : (testMatcher.group(4) != null ? testMatcher.group(4)
                    : (testMatcher.group(6) != null ? testMatcher.group(6) : testMatcher.group(8)));
            String rootCause = "Test assertion failed: expected <" + expected + "> but was <" + actual + ">";
            List<String> constraints = new ArrayList<>();
            constraints.add("Do NOT assume logic produces <" + actual + "> when test expects <" + expected + ">.");
            String pivot = "Align implementation logic with expected test value: " + expected;
            return new FailureAnalysis(FailureCategory.TEST_FAILURE, rootCause, constraints, pivot);
        }

        Matcher testGenericMatcher = TEST_ASSERTION_GENERIC.matcher(errorText);
        if (testGenericMatcher.find()) {
            String msg = testGenericMatcher.group(1);
            String rootCause = "Test assertion failure" + (msg != null ? ": " + msg.trim() : ".");
            List<String> constraints = new ArrayList<>();
            constraints.add("Do NOT proceed without resolving failing unit/integration test assertions.");
            String pivot = "Inspect test assertion details and debug state transitions in the subject under test.";
            return new FailureAnalysis(FailureCategory.TEST_FAILURE, rootCause, constraints, pivot);
        }

        Matcher testSumMatcher = TEST_FAILURES_SUMMARY.matcher(errorText);
        if (testSumMatcher.find()) {
            String fails = testSumMatcher.group(1) != null ? testSumMatcher.group(1) : testSumMatcher.group(2);
            String rootCause = "Test suite failed with " + fails + " failure(s).";
            List<String> constraints = new ArrayList<>();
            constraints.add("Do NOT commit changes while unit tests fail.");
            String pivot = "Run individual failing tests with verbose output to isolate regressions.";
            return new FailureAnalysis(FailureCategory.TEST_FAILURE, rootCause, constraints, pivot);
        }

        // 4. File Not Found
        Matcher fnfMatcher = FILE_NOT_FOUND_PATTERN.matcher(errorText);
        if (fnfMatcher.find()) {
            String path = fnfMatcher.group(1);
            String target = (path != null && !path.trim().isEmpty()) ? path.trim() : payload;
            String rootCause = "File or resource not found: " + (target.isEmpty() ? "specified path" : target);
            List<String> constraints = new ArrayList<>();
            constraints.add("Do NOT read, modify, or execute non-existent path: " + (target.isEmpty() ? "unverified file" : target));
            String pivot = "Check file existence with list_dir or codebase_search before attempting access, or create missing file.";
            return new FailureAnalysis(FailureCategory.FILE_NOT_FOUND, rootCause, constraints, pivot);
        }

        // 5. Tool Parameter Error
        Matcher paramMatcher = TOOL_PARAMETER_PATTERN.matcher(errorText);
        if (paramMatcher.find()) {
            String paramDetails = paramMatcher.group(1);
            String rootCause = "Tool parameter error for '" + tool + "'" + (paramDetails != null ? ": " + paramDetails : ".");
            List<String> constraints = new ArrayList<>();
            constraints.add("Do NOT invoke tool '" + tool + "' with missing or malformed required parameters.");
            String pivot = "Review the tool schema declaration for '" + tool + "' and provide all mandatory arguments.";
            return new FailureAnalysis(FailureCategory.TOOL_PARAMETER_ERROR, rootCause, constraints, pivot);
        }

        // 6. Timeout
        Matcher timeoutMatcher = TIMEOUT_PATTERN.matcher(errorText);
        if (timeoutMatcher.find()) {
            String rootCause = "Execution timed out during operation: " + tool;
            List<String> constraints = new ArrayList<>();
            constraints.add("Do NOT invoke long-running, blocking operations without pagination or smaller batch sizes.");
            String pivot = "Optimize execution, narrow the query scope, or increase tool timeout limits.";
            return new FailureAnalysis(FailureCategory.STALL_OR_TIMEOUT, rootCause, constraints, pivot);
        }

        // 7. Runtime Exception
        Matcher runtimeMatcher = RUNTIME_EXCEPTION_PATTERN.matcher(errorText);
        if (runtimeMatcher.find()) {
            String exType = runtimeMatcher.group(1);
            String details = runtimeMatcher.group(2);
            String rootCause = "Runtime exception occurred: " + exType + (details != null ? " (" + details.trim() + ")" : "");
            List<String> constraints = new ArrayList<>();
            constraints.add("Do NOT trigger " + exType + " with unvalidated nulls, boundary indexes, or illegal state.");
            String pivot = "Add defensive null checks, bounds validation, and precondition guards before invoking the operation.";
            return new FailureAnalysis(FailureCategory.UNEXPECTED_EXCEPTION, rootCause, constraints, pivot);
        }

        // Fallback: Unknown Failure
        String snippet = errorText.length() > 200 ? errorText.substring(0, 200) + "..." : errorText;
        String rootCause = "Unclassified trajectory failure: " + (snippet.isEmpty() ? "No error details available." : snippet);
        List<String> constraints = new ArrayList<>();
        constraints.add("Do NOT repeat failed action sequence without altering approach or state.");
        String pivot = "Formulate a new hypothesis, inspect logs/diagnostics, and pivot to an alternative problem-solving strategy.";
        return new FailureAnalysis(FailureCategory.UNKNOWN, rootCause, constraints, pivot);
    }

    private static class FailureAnalysis {
        final FailureCategory category;
        final String rootCause;
        final List<String> negativeConstraints;
        final String suggestedPivot;

        FailureAnalysis(FailureCategory category, String rootCause, List<String> negativeConstraints, String suggestedPivot) {
            this.category = category;
            this.rootCause = rootCause;
            this.negativeConstraints = negativeConstraints;
            this.suggestedPivot = suggestedPivot;
        }
    }
}
