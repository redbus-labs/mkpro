package com.mkpro.routing;

import static com.mkpro.ui.AnsiColors.*;

import com.mkpro.events.MkProEvent;
import com.mkpro.events.MkProEventBus;
import com.mkpro.knowledge.KnowledgeScheduler;
import com.mkpro.knowledge.KnowledgeStore;
import com.mkpro.knowledge.TopicConfig;
import com.mkpro.knowledge.TopicIndex;
import com.mkpro.knowledge.TopicReport;

import java.util.List;
import java.util.function.Function;

/**
 * Handles knowledge adequacy checking for the MakerLoop.
 * 
 * Responsibilities:
 * - Pre-goal knowledge gap detection and proactive acquisition
 * - Reactive knowledge acquisition when agent responses show uncertainty
 * - Retrospective analysis correlating knowledge with goal outcomes
 */
public class KnowledgeAdequacyChecker {

    static final int KNOWLEDGE_WAIT_SECONDS = 45;
    static final int MAX_KNOWLEDGE_ACQUISITIONS_PER_GOAL = 2;

    private volatile KnowledgeScheduler knowledgeScheduler;
    private volatile KnowledgeStore knowledgeStore;
    private volatile TopicIndex topicIndex;
    private volatile Function<String, String> llmCallback;

    private volatile MkProEventBus eventBus;
    private volatile MarkovRouter router;

    public KnowledgeAdequacyChecker() {
    }

    /**
     * Wire knowledge components for proactive gap detection.
     */
    public void setKnowledgeComponents(KnowledgeScheduler scheduler, KnowledgeStore store, TopicIndex index) {
        this.knowledgeScheduler = scheduler;
        this.knowledgeStore = store;
        this.topicIndex = index;
    }

    /**
     * Set LLM callback for generating knowledge topic suggestions.
     * Function takes a prompt string and returns LLM response.
     */
    public void setLlmCallback(Function<String, String> callback) {
        this.llmCallback = callback;
    }

    public void setEventBus(MkProEventBus eventBus) {
        this.eventBus = eventBus;
    }

    public void setRouter(MarkovRouter router) {
        this.router = router;
    }

    // Accessors for backward compatibility (MakerLoop delegates its public setters here)
    public KnowledgeScheduler getKnowledgeScheduler() { return knowledgeScheduler; }
    public KnowledgeStore getKnowledgeStore() { return knowledgeStore; }
    public TopicIndex getTopicIndex() { return topicIndex; }
    public Function<String, String> getLlmCallback() { return llmCallback; }

    /**
     * Pre-goal check: detect if the goal domain has knowledge coverage.
     * If not, ask LLM to suggest a topic and schedule immediate acquisition.
     */
    public void checkPreGoal(String goalText, MakerState currentGoal) {
        checkAndAcquireKnowledge(goalText, currentGoal);
    }

    /**
     * Reactive check: detect uncertainty in agent response and acquire knowledge if needed.
     * @return true if knowledge was acquired and a retry is recommended
     */
    public boolean reactiveCheck(String response, MakerState currentGoal) {
        if (currentGoal == null || response == null) return false;
        if (currentGoal.getKnowledgeRetries() >= MAX_KNOWLEDGE_ACQUISITIONS_PER_GOAL) return false;

        double uncertaintyScore = detectUncertainty(response);
        if (uncertaintyScore >= 0.5) {
            boolean acquired = reactiveKnowledgeAcquire(response, currentGoal);
            if (acquired) {
                currentGoal.incrementKnowledgeRetries();
                String msg = "Response uncertain (" + (int)(uncertaintyScore * 100) + "%). Acquired knowledge → retrying.";
                if (eventBus != null) eventBus.emit(MkProEvent.system(msg));
                else System.out.println(ANSI_YELLOW + "  [Maker] " + msg + ANSI_RESET);
                return true;
            }
        }
        return false;
    }

    /**
     * Post-goal retrospective: correlate knowledge acquisition with goal outcome.
     * Logs insights about whether knowledge helped and what might be missing.
     */
    public void retrospective(MakerState currentGoal, boolean success) {
        retrospectiveKnowledgeAnalysis(currentGoal, success);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal methods (extracted from MakerLoop)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Check if the goal domain has knowledge coverage. If not, ask LLM to suggest
     * a topic and schedule immediate acquisition.
     */
    private void checkAndAcquireKnowledge(String goalText, MakerState currentGoal) {
        // Guard: need all components + non-trivial goal
        if (knowledgeScheduler == null || topicIndex == null || llmCallback == null) {
            if (knowledgeScheduler == null) System.out.println(ANSI_YELLOW + "  [Maker] Knowledge check skipped: scheduler not wired" + ANSI_RESET);
            else if (topicIndex == null) System.out.println(ANSI_YELLOW + "  [Maker] Knowledge check skipped: topicIndex not wired" + ANSI_RESET);
            else System.out.println(ANSI_YELLOW + "  [Maker] Knowledge check skipped: llmCallback not wired" + ANSI_RESET);
            return;
        }
        if (goalText == null || goalText.split("\\s+").length < 4) return;

        try {
            // Check existing coverage
            List<TopicIndex.SearchResult> results = topicIndex.search(goalText, 3);
            if (!results.isEmpty() && results.get(0).getScore() > 0.3) {
                // Already have relevant knowledge — log it
                String topicName = results.get(0).getTopicName();
                int score = (int)(results.get(0).getScore() * 100);
                String msg = "Knowledge adequate: " + topicName + " (score: " + score + "%) — skipping acquisition";
                if (eventBus != null) eventBus.emit(MkProEvent.system(msg));
                else System.out.println(ANSI_GREEN + "  [Maker] " + msg + ANSI_RESET);
                return;
            }

            // Ask LLM to suggest a knowledge topic
            String prompt = buildKnowledgeSuggestionPrompt(goalText);
            String response = llmCallback.apply(prompt);
            if (response == null || response.isBlank()) return;

            // Parse suggestion
            TopicConfig topic = parseKnowledgeSuggestion(response);
            if (topic == null) {
                // LLM said NONE or unparseable — no external knowledge needed
                String msg = "Knowledge check: no external docs needed for this goal";
                if (eventBus != null) eventBus.emit(MkProEvent.system(msg));
                else System.out.println(ANSI_GREEN + "  [Maker] " + msg + ANSI_RESET);
                return;
            }

            // Schedule acquisition
            boolean added = knowledgeScheduler.addTopic(topic);
            if (!added) return; // Already exists

            String msg = "Knowledge scheduled: " + topic.getName() + " (will be ready for next turn)";
            if (eventBus != null) eventBus.emit(MkProEvent.system(msg));
            else System.out.println(ANSI_PURPLE + "  [Maker] " + msg + ANSI_RESET);

            // Non-blocking: don't wait for fetch. The scheduler will fetch in background.
            // Knowledge will be available via request_knowledge tool on next turn.
            currentGoal.addAcquiredTopic(topic.getName());

        } catch (Exception e) {
            // Non-fatal — proceed without knowledge
            System.out.println(ANSI_YELLOW + "  [Maker] Knowledge gap check failed: " + e.getMessage() + ANSI_RESET);
        }
    }

    String buildKnowledgeSuggestionPrompt(String goalText) {
        return "A developer wants to accomplish this goal:\n\"" + goalText + "\"\n\n" +
            "Determine if this goal requires specialized knowledge that an AI agent might not have " +
            "(e.g., specific API docs, framework guides, best practices from official sources).\n\n" +
            "If YES, respond with EXACTLY this format (no extra text):\n" +
            "TOPIC:<lowercase-hyphen-name>\n" +
            "URL:<official documentation or reference URL>\n" +
            "FOCUS:<what to analyze, max 1 sentence>\n\n" +
            "If NO (the goal is simple, generic, or already well-known), respond with just: NONE\n\n" +
            "Rules:\n" +
            "- Only suggest official documentation URLs (docs.*, developer.*, official guides)\n" +
            "- Topic name should be specific (e.g., 'k8s-hpa-autoscaling' not 'kubernetes')\n" +
            "- Only suggest if the knowledge would meaningfully help the task";
    }

    /**
     * Parse LLM response into a TopicConfig.
     * Expected format:
     *   TOPIC:name
     *   URL:https://...
     *   FOCUS:instruction
     */
    TopicConfig parseKnowledgeSuggestion(String response) {
        if (response.trim().equalsIgnoreCase("NONE")) return null;

        String name = null, url = null, focus = null;

        for (String line : response.split("\n")) {
            line = line.trim();
            if (line.startsWith("TOPIC:")) name = line.substring(6).trim();
            else if (line.startsWith("URL:")) url = line.substring(4).trim();
            else if (line.startsWith("FOCUS:")) focus = line.substring(6).trim();
        }

        if (name == null || name.isBlank() || url == null || url.isBlank()) return null;

        TopicConfig topic = new TopicConfig();
        topic.setName(name.toLowerCase().replaceAll("[^a-z0-9-]", "-"));
        topic.setTitle(name);
        topic.setSources(List.of(url));
        topic.setInstruction(focus != null ? focus : "Analyze for practical implementation guidance.");
        topic.setRefreshIntervalMinutes(360); // Low refresh — just need initial fetch
        return topic;
    }

    /**
     * Wait for a topic report to be ready in the store.
     * @return true if ready within timeout
     */
    boolean waitForTopicReady(String topicName, int maxWaitSeconds) {
        for (int i = 0; i < maxWaitSeconds; i++) {
            TopicReport report = knowledgeStore.getReport(topicName);
            if (report != null && report.getSummary() != null && !report.getSummary().isBlank()) {
                return true;
            }
            try { Thread.sleep(1000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Detect uncertainty/weakness in an agent's response.
     * Returns a score 0.0–1.0. Scores >= 0.5 suggest inadequate knowledge.
     */
    double detectUncertainty(String response) {
        if (response == null || response.isBlank()) return 0.0;

        String lower = response.toLowerCase();
        int signals = 0;

        // Strong uncertainty signals
        String[] strongSignals = {
            "i'm not sure", "i am not sure", "i don't know", "i'm uncertain",
            "i cannot confirm", "i don't have enough information",
            "you should verify", "you should check", "please consult",
            "i would recommend checking", "i'm not aware of",
            "this may not be accurate", "i cannot guarantee"
        };
        for (String s : strongSignals) {
            if (lower.contains(s)) signals += 3;
        }

        // Moderate uncertainty signals
        String[] moderateSignals = {
            "generally", "typically", "usually", "in most cases",
            "might be", "could be", "may vary", "it depends",
            "from what i recall", "as far as i know", "i believe",
            "not entirely sure", "double-check", "worth verifying"
        };
        for (String s : moderateSignals) {
            if (lower.contains(s)) signals += 1;
        }

        // Hedging language (weaker signal)
        String[] hedging = {
            "probably", "perhaps", "possibly", "seemingly",
            "it seems", "it appears", "it looks like"
        };
        for (String s : hedging) {
            if (lower.contains(s)) signals += 1;
        }

        // Normalize: 5+ signal points = fully uncertain
        return Math.min(1.0, signals / 5.0);
    }

    /**
     * Reactively acquire knowledge based on an uncertain response.
     * Extracts what the agent was struggling with and schedules a targeted fetch.
     * @return true if knowledge was acquired successfully
     */
    private boolean reactiveKnowledgeAcquire(String response, MakerState currentGoal) {
        if (knowledgeScheduler == null || llmCallback == null || currentGoal == null) return false;

        try {
            // Ask LLM: "What specific knowledge gap does this response reveal?"
            String prompt = "An AI agent gave this response to the goal \"" + truncate(currentGoal.getGoalDescription(), 100) + "\":\n\n" +
                "---\n" + truncate(response, 800) + "\n---\n\n" +
                "The response shows uncertainty or lacks specificity. " +
                "What specific knowledge would help? Suggest ONE official documentation source.\n\n" +
                "Respond with EXACTLY this format:\n" +
                "TOPIC:<lowercase-hyphen-name>\n" +
                "URL:<official documentation URL>\n" +
                "FOCUS:<what to look for, max 1 sentence>\n\n" +
                "If the response is actually adequate and doesn't need external knowledge, respond: NONE";

            String suggestion = llmCallback.apply(prompt);
            if (suggestion == null || suggestion.trim().equalsIgnoreCase("NONE")) return false;

            TopicConfig topic = parseKnowledgeSuggestion(suggestion);
            if (topic == null) return false;

            // Don't re-acquire the same topic
            if (currentGoal.getAcquiredTopics().contains(topic.getName())) return false;

            boolean added = knowledgeScheduler.addTopic(topic);
            if (!added) return false;

            currentGoal.addAcquiredTopic(topic.getName());

            String msg = "Reactive knowledge scheduled: " + topic.getName() + " (will be ready for retry)";
            if (eventBus != null) eventBus.emit(MkProEvent.system(msg));
            else System.out.println(ANSI_PURPLE + "  [Maker] " + msg + ANSI_RESET);

            // Don't wait — the retry cycle gives the scheduler time to fetch.
            // Knowledge will be available via request_knowledge tool on next turn.
            return true;
        } catch (Exception e) {
            // Non-fatal
        }
        return false;
    }

    /**
     * Post-goal retrospective: correlate knowledge acquisition with goal outcome.
     */
    private void retrospectiveKnowledgeAnalysis(MakerState currentGoal, boolean success) {
        if (currentGoal == null) return;

        List<String> acquired = currentGoal.getAcquiredTopics();
        int knowledgeRetries = currentGoal.getKnowledgeRetries();

        // Nothing to analyze if no knowledge was involved
        if (acquired.isEmpty() && !currentGoal.isPreGoalKnowledgeUsed()) return;

        StringBuilder insight = new StringBuilder();
        insight.append("[Knowledge Retrospective] Goal: \"")
               .append(truncate(currentGoal.getGoalDescription(), 50)).append("\" → ");

        if (success) {
            if (knowledgeRetries > 0) {
                // Knowledge retry led to success — the acquisition helped
                insight.append("SUCCESS after ").append(knowledgeRetries).append(" knowledge retry(ies). Topics: ")
                       .append(String.join(", ", acquired));
                currentGoal.incrementKnowledgeRetrySuccesses();
            } else if (!acquired.isEmpty()) {
                // Pre-goal knowledge was available, goal succeeded
                insight.append("SUCCESS with pre-acquired knowledge: ").append(String.join(", ", acquired));
            } else {
                insight.append("SUCCESS (pre-goal knowledge present)");
            }
        } else {
            // Goal failed/escalated — was knowledge a factor?
            if (acquired.isEmpty()) {
                // Failed without any knowledge acquisition — might have helped
                insight.append("FAILED. No knowledge was acquired. Consider if domain docs would help.");
                // Schedule a retrospective acquisition for future similar goals
                scheduleRetrospectiveKnowledge(currentGoal);
            } else {
                // Failed even with acquired knowledge — knowledge was insufficient
                insight.append("FAILED despite acquiring: ").append(String.join(", ", acquired))
                       .append(". Knowledge may have been insufficient or wrong sources.");
            }
        }

        // Log the insight
        String msg = insight.toString();
        if (eventBus != null) {
            eventBus.emit(MkProEvent.system(msg));
        } else {
            System.out.println(ANSI_PURPLE + "  " + msg + ANSI_RESET);
        }

        // Store in router's memory for future pattern matching
        if (router != null) {
            router.recordKnowledgeOutcome(currentGoal.getCategory(), acquired, success, knowledgeRetries);
        }
    }

    /**
     * When a goal fails without knowledge, schedule a retrospective acquisition
     * so that future similar goals will have coverage.
     */
    private void scheduleRetrospectiveKnowledge(MakerState currentGoal) {
        if (knowledgeScheduler == null || llmCallback == null || currentGoal == null) return;
        // Only if we haven't already tried to acquire for this goal
        if (!currentGoal.getAcquiredTopics().isEmpty()) return;

        try {
            String prompt = "A developer's goal FAILED: \"" + currentGoal.getGoalDescription() + "\"\n" +
                "The failure may have been caused by lack of domain knowledge.\n" +
                "Category: " + currentGoal.getCategory() + "\n" +
                "Agents tried: " + currentGoal.getAgentSequence() + "\n\n" +
                "What knowledge should be pre-fetched for FUTURE similar goals?\n\n" +
                "Respond with EXACTLY this format:\n" +
                "TOPIC:<lowercase-hyphen-name>\n" +
                "URL:<official documentation URL>\n" +
                "FOCUS:<what to pre-learn>\n\n" +
                "If no external knowledge would help (e.g., project-specific issue), respond: NONE";

            String suggestion = llmCallback.apply(prompt);
            if (suggestion == null || suggestion.trim().equalsIgnoreCase("NONE")) return;

            TopicConfig topic = parseKnowledgeSuggestion(suggestion);
            if (topic == null) return;

            topic.setRefreshIntervalMinutes(720); // Low priority — background enrichment
            knowledgeScheduler.addTopic(topic);

            String msg = "Retrospective: scheduled '" + topic.getName() + "' for future goals.";
            if (eventBus != null) eventBus.emit(MkProEvent.system(msg));
            else System.out.println(ANSI_PURPLE + "  [Maker] " + msg + ANSI_RESET);

        } catch (Exception e) {
            // Non-fatal
        }
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
