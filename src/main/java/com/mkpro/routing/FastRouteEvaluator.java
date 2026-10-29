package com.mkpro.routing;

import com.mkpro.core.MkProContext;
import com.mkpro.routing.IntentClassifier.TaskCategory;
import com.mkpro.routing.MarkovRouter.RoutingDecision;

public class FastRouteEvaluator {

    public static final double CONFIDENCE_THRESHOLD = 0.65;
    public static final int MIN_OBSERVATIONS = 20;

    public static class EvaluationResult {
        public final boolean shouldDirectDispatch;
        public final String targetAgent;
        public final double confidence;
        public final TaskCategory category;
        public final String reason;

        public EvaluationResult(boolean shouldDirectDispatch, String targetAgent, double confidence, TaskCategory category, String reason) {
            this.shouldDirectDispatch = shouldDirectDispatch;
            this.targetAgent = targetAgent;
            this.confidence = confidence;
            this.category = category;
            this.reason = reason;
        }

        public static EvaluationResult noDispatch(String reason) {
            return new EvaluationResult(false, null, 0.0, TaskCategory.GENERAL, reason);
        }

        public String formatRoutingGuidance() {
            if (!shouldDirectDispatch || targetAgent == null) {
                return null;
            }
            int pct = (int) Math.round(confidence * 100);
            String catName = category != null ? category.name() : "GENERAL";
            
            // Normalize "CodeEditor" to "Coder" for tool suffix
            String agentName = "CodeEditor".equalsIgnoreCase(targetAgent) ? "Coder" : targetAgent;
            String toolSuffix = agentName.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
            
            return String.format(
                "[Routing Guidance: Recommended specialist agent is '%s' (category: %s, confidence: %d%%, reason: %s). " +
                "Please coordinate and delegate to `ask_%s` as appropriate.]",
                agentName, catName, pct, reason, toolSuffix
            );
        }
    }

    /**
     * Evaluates whether an incoming user prompt qualifies for high-confidence routing guidance.
     */
    public static EvaluationResult evaluate(String input, MkProContext context) {
        if (input == null || input.trim().isEmpty() || context == null) {
            return EvaluationResult.noDispatch("empty input or null context");
        }

        String trimmed = input.trim();
        String lower = trimmed.toLowerCase();

        // Guardrail 1: Compound / multi-step planning instructions require Coordinator planning
        if (lower.contains(" then ") || lower.contains(" and then ") || lower.contains(" after that ")
                || lower.contains(" and also ") || lower.contains(" step 1 ")
                || lower.matches("(?i).*\\bfirst\\b.*\\bthen\\b.*")) {
            return EvaluationResult.noDispatch("compound or multi-step query requires coordinator planning");
        }

        MarkovRouter router = context.getMarkovRouter();
        if (router == null || router.getTotalObservations() <= MIN_OBSERVATIONS) {
            return EvaluationResult.noDispatch("insufficient markov observations (<= 20)");
        }

        IntentClassifier classifier = new IntentClassifier();
        if (router.getLearnedPatterns() != null) {
            classifier.setLearnedPatterns(router.getLearnedPatterns());
        }

        // Direct YAML agent keyword match (e.g. "@coder", "coder", explicit agent directive)
        String directAgent = classifier.classifyToAgent(trimmed);
        if (directAgent != null && !"Coordinator".equalsIgnoreCase(directAgent)) {
            if ("CodeEditor".equalsIgnoreCase(directAgent)) {
                directAgent = "Coder";
            }
            TaskCategory cat = classifier.classify(trimmed);
            return new EvaluationResult(true, directAgent, 1.0, cat, "direct_keyword_match");
        }

        // Intent category & confidence evaluation
        TaskCategory category = classifier.classify(trimmed);
        double intentConfidence = classifier.classifyWithConfidence(trimmed);

        if (intentConfidence >= CONFIDENCE_THRESHOLD && category != TaskCategory.GENERAL) {
            RoutingDecision decision = router.route(category, null);
            if (decision != null && decision.shouldRoute 
                    && !"Coordinator".equalsIgnoreCase(decision.agent) 
                    && decision.confidence >= CONFIDENCE_THRESHOLD) {
                
                String agent = decision.agent;
                // Normalize for Markov-based predictions
                if ("CodeEditor".equalsIgnoreCase(agent)) {
                    agent = "Coder";
                }
                
                return new EvaluationResult(true, agent, decision.confidence, category, "markov_high_confidence");
            }
        }

        return EvaluationResult.noDispatch("confidence below threshold or general task");
    }
}
