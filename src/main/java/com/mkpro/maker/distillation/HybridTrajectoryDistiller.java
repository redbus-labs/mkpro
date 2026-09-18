package com.mkpro.maker.distillation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hybrid trajectory distillation engine combining deterministic rule-based
 * heuristic extraction with optional LLM-powered semantic distillation and refinement.
 */
public class HybridTrajectoryDistiller {

    private static final Pattern CATEGORY_TAG = Pattern.compile("(?i)<category>\\s*([a-zA-Z0-9_]+)\\s*</category>");
    private static final Pattern ROOT_CAUSE_TAG = Pattern.compile("(?i)<root_cause>\\s*([\\s\\S]*?)\\s*</root_cause>");
    private static final Pattern FAILED_HYPOTHESIS_TAG = Pattern.compile("(?i)<failed_hypothesis>\\s*([\\s\\S]*?)\\s*</failed_hypothesis>");
    private static final Pattern CONSTRAINT_TAG = Pattern.compile("(?i)<constraint>\\s*([\\s\\S]*?)\\s*</constraint>");
    private static final Pattern SUGGESTED_PIVOT_TAG = Pattern.compile("(?i)<suggested_pivot>\\s*([\\s\\S]*?)\\s*</suggested_pivot>");

    private final TrajectoryDistiller ruleDistiller;
    private final Function<String, String> llmProvider;

    public HybridTrajectoryDistiller() {
        this(new TrajectoryDistiller(), null);
    }

    public HybridTrajectoryDistiller(Function<String, String> llmProvider) {
        this(new TrajectoryDistiller(), llmProvider);
    }

    public HybridTrajectoryDistiller(TrajectoryDistiller ruleDistiller, Function<String, String> llmProvider) {
        this.ruleDistiller = (ruleDistiller != null) ? ruleDistiller : new TrajectoryDistiller();
        this.llmProvider = llmProvider;
    }

    public DistilledLesson distill(ExecutionTrajectory trajectory, String response) {
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

        // Fast-path: deterministic rule-based distillation
        DistilledLesson baseLesson = ruleDistiller.distill(trajectory);

        // If no LLM provider is available or trajectory succeeded, return rule-based lesson directly
        if (llmProvider == null || (trajectory.isSuccess() && trajectory.getFailedSteps().isEmpty())) {
            return baseLesson;
        }

        try {
            // Build distillation prompt for LLM
            String prompt = buildPrompt(trajectory, baseLesson);
            String llmResponse = llmProvider.apply(prompt);

            if (llmResponse != null && !llmResponse.isBlank()) {
                DistilledLesson parsed = parseLlmResponse(llmResponse, baseLesson);
                if (parsed != null) {
                    return parsed;
                }
            }
        } catch (Exception e) {
            // Gracefully fallback to deterministic distillation on any LLM or parsing failure
        }

        return baseLesson;
    }

    public TrajectoryDistiller getRuleDistiller() {
        return ruleDistiller;
    }

    public Function<String, String> getLlmProvider() {
        return llmProvider;
    }

    private String buildPrompt(ExecutionTrajectory trajectory, DistilledLesson baseLesson) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert failure analyst. Analyze the following failed execution trajectory and distill key lessons.\n\n");
        sb.append("Goal ID: ").append(trajectory.getGoalId()).append("\n");
        sb.append("Initial Hypothesis: ").append(trajectory.getInitialHypothesis()).append("\n");
        sb.append("Trajectory Signature: ").append(trajectory.getSignature()).append("\n");
        sb.append("Last Error / Output: ").append(trajectory.getLastError()).append("\n\n");

        if (baseLesson != null) {
            sb.append("Initial Heuristic Lesson:\n").append(baseLesson.toPromptEnvelope()).append("\n\n");
        }

        sb.append("Provide a refined distilled lesson in the following XML format:\n");
        sb.append("<distilled_lesson>\n");
        sb.append("  <category>COMPILE_ERROR|TEST_FAILURE|SYNTAX_ERROR|SECURITY_POLICY_VIOLATION|FILE_NOT_FOUND|COMMAND_EXECUTION_FAILURE|TOOL_PARAMETER_ERROR|STALL_OR_TIMEOUT|UNEXPECTED_EXCEPTION|AGENT_PROMISE_BREACH|UNKNOWN</category>\n");
        sb.append("  <root_cause>Detailed root cause</root_cause>\n");
        sb.append("  <failed_hypothesis>Hypothesis that failed</failed_hypothesis>\n");
        sb.append("  <negative_constraints>\n");
        sb.append("    <constraint>Do NOT ...</constraint>\n");
        sb.append("  </negative_constraints>\n");
        sb.append("  <suggested_pivot>Actionable alternative step</suggested_pivot>\n");
        sb.append("</distilled_lesson>");

        return sb.toString();
    }

    private DistilledLesson parseLlmResponse(String response, DistilledLesson fallback) {
        if (!response.contains("<distilled_lesson>")) {
            return null;
        }

        DistilledLesson.Builder builder = DistilledLesson.builder();
        if (fallback != null) {
            builder.lessonId(fallback.getLessonId());
            builder.category(fallback.getCategory());
            builder.rootCauseSummary(fallback.getRootCauseSummary());
            builder.failedHypothesis(fallback.getFailedHypothesis());
            builder.negativeConstraints(fallback.getNegativeConstraints());
            builder.suggestedPivot(fallback.getSuggestedPivot());
        }

        Matcher catMatcher = CATEGORY_TAG.matcher(response);
        if (catMatcher.find()) {
            try {
                builder.category(FailureCategory.valueOf(catMatcher.group(1).trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }

        Matcher rcMatcher = ROOT_CAUSE_TAG.matcher(response);
        if (rcMatcher.find()) {
            builder.rootCauseSummary(rcMatcher.group(1).trim());
        }

        Matcher fhMatcher = FAILED_HYPOTHESIS_TAG.matcher(response);
        if (fhMatcher.find()) {
            builder.failedHypothesis(fhMatcher.group(1).trim());
        }

        Matcher constraintMatcher = CONSTRAINT_TAG.matcher(response);
        List<String> constraints = new ArrayList<>();
        while (constraintMatcher.find()) {
            String c = constraintMatcher.group(1).trim();
            if (!c.isEmpty()) {
                constraints.add(c);
            }
        }
        if (!constraints.isEmpty()) {
            builder.negativeConstraints(constraints);
        }

        Matcher spMatcher = SUGGESTED_PIVOT_TAG.matcher(response);
        if (spMatcher.find()) {
            builder.suggestedPivot(spMatcher.group(1).trim());
        }

        return builder.build();
    }
}
