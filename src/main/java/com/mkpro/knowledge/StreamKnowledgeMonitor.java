package com.mkpro.knowledge;

import com.mkpro.routing.MakerLoop;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * StreamKnowledgeMonitor scans agent response chunks in real-time during streaming
 * to detect knowledge gaps and pre-fetch relevant docs in the background.
 *
 * Unlike the post-turn reactive approach (which forces a RETRY), this:
 * - Runs non-blocking during the current turn
 * - Doesn't interrupt the agent's response
 * - Pre-fetches so the NEXT turn benefits via request_knowledge tool
 *
 * Gap signals detected:
 * - Explicit uncertainty phrases ("I'm not familiar with...", "I don't have details on...")
 * - Tool errors (command not found, API failures, permission denied)
 * - fetch_url calls (agent already trying to get external info)
 * - Delegation about unfamiliar domains
 */
public class StreamKnowledgeMonitor {

    private static final int SCAN_INTERVAL_CHARS = 500;
    private static final int MAX_FETCHES_PER_STREAM = 2;

    // Gap signal patterns — phrases indicating knowledge inadequacy
    private static final String[] EXPLICIT_GAP_SIGNALS = {
        "i'm not familiar with",
        "i don't have details on",
        "i don't have information about",
        "i'm not sure about the specifics",
        "i don't know the exact",
        "i lack information on",
        "i cannot find documentation",
        "i need to look up",
        "i need more context about",
        "unfamiliar with this",
        "outside my knowledge",
        "i don't have access to the docs",
        "would need to check the documentation",
        "i'm unsure of the correct"
    };

    // Tool error signals
    private static final String[] TOOL_ERROR_SIGNALS = {
        "command not found",
        "no such file or directory",
        "permission denied",
        "connection refused",
        "404 not found",
        "unknown command",
        "unrecognized option",
        "module not found",
        "package not found",
        "import error"
    };

    // Domain delegation signals
    private static final String[] DELEGATION_SIGNALS = {
        "you may want to consult",
        "refer to the official documentation",
        "check the docs for",
        "see the official guide",
        "according to documentation"
    };

    private final KnowledgeScheduler scheduler;
    private final TopicIndex topicIndex;
    private final Function<String, String> llmCallback;
    private final ExecutorService backgroundExecutor;

    // Per-stream state
    private final StringBuilder buffer = new StringBuilder();
    private int lastScanPosition = 0;
    private int fetchesTriggered = 0;
    private final Set<String> triggeredTopics = new HashSet<>();
    private volatile boolean active = true;

    public StreamKnowledgeMonitor(KnowledgeScheduler scheduler, TopicIndex topicIndex, Function<String, String> llmCallback) {
        this.scheduler = scheduler;
        this.topicIndex = topicIndex;
        this.llmCallback = llmCallback;
        this.backgroundExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "stream-knowledge-monitor");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Feed a streaming chunk. Called for each chunk as the agent responds.
     * Non-blocking — any background fetch happens asynchronously.
     */
    public void onChunk(String chunk) {
        if (!active || chunk == null || chunk.isEmpty()) return;
        if (fetchesTriggered >= MAX_FETCHES_PER_STREAM) return;

        buffer.append(chunk);

        // Scan every SCAN_INTERVAL_CHARS
        if (buffer.length() - lastScanPosition >= SCAN_INTERVAL_CHARS) {
            String newContent = buffer.substring(lastScanPosition);
            lastScanPosition = buffer.length();
            scanForGaps(newContent);
        }
    }

    /**
     * Called when the stream ends. Performs final scan on any remaining buffer.
     */
    public void onStreamEnd() {
        if (!active) return;
        if (buffer.length() > lastScanPosition) {
            String remaining = buffer.substring(lastScanPosition);
            scanForGaps(remaining);
        }
        active = false;
    }

    /**
     * Reset for a new stream (new turn).
     */
    public void reset() {
        buffer.setLength(0);
        lastScanPosition = 0;
        fetchesTriggered = 0;
        triggeredTopics.clear();
        active = true;
    }

    /**
     * Shutdown the background executor.
     */
    public void shutdown() {
        active = false;
        backgroundExecutor.shutdownNow();
    }

    /**
     * Get topics fetched during this stream (for logging/tracking).
     */
    public Set<String> getTriggeredTopics() {
        return new HashSet<>(triggeredTopics);
    }

    // ═══ Private ═══

    private void scanForGaps(String text) {
        if (text == null || text.isBlank()) return;
        String lower = text.toLowerCase();

        // Check explicit gap signals
        for (String signal : EXPLICIT_GAP_SIGNALS) {
            if (lower.contains(signal)) {
                triggerBackgroundFetch(text, "explicit_gap: " + signal);
                return;
            }
        }

        // Check tool error signals
        for (String signal : TOOL_ERROR_SIGNALS) {
            if (lower.contains(signal)) {
                triggerBackgroundFetch(text, "tool_error: " + signal);
                return;
            }
        }

        // Check delegation signals (weaker — only trigger if combined with uncertainty)
        for (String signal : DELEGATION_SIGNALS) {
            if (lower.contains(signal)) {
                // Only trigger if there's also some hedging
                if (lower.contains("might") || lower.contains("may") || lower.contains("should")
                    || lower.contains("could") || lower.contains("possibly")) {
                    triggerBackgroundFetch(text, "delegation_with_uncertainty: " + signal);
                    return;
                }
            }
        }

        // Check for fetch_url tool call (agent is already trying to get external info)
        if (lower.contains("[fetchurl]") || lower.contains("fetch_url") || lower.contains("fetching url")) {
            // Extract URL context and pre-fetch related knowledge
            triggerBackgroundFetch(text, "fetch_url_detected");
        }
    }

    private void triggerBackgroundFetch(String contextText, String reason) {
        if (fetchesTriggered >= MAX_FETCHES_PER_STREAM) return;
        if (scheduler == null || llmCallback == null) return;

        fetchesTriggered++;

        // Run in background — don't block streaming
        final String ctx = contextText.length() > 600 ? contextText.substring(contextText.length() - 600) : contextText;
        final String fetchReason = reason;

        backgroundExecutor.submit(() -> {
            try {
                // Check if we already have coverage
                if (topicIndex != null) {
                    List<TopicIndex.SearchResult> existing = topicIndex.search(ctx, 1);
                    if (!existing.isEmpty() && existing.get(0).getScore() > 0.4) {
                        return; // Already covered
                    }
                }

                // Ask LLM what knowledge is needed
                String prompt = "An AI agent is currently responding and showed a knowledge gap.\n" +
                    "Gap signal: " + fetchReason + "\n" +
                    "Context (last 600 chars of response):\n---\n" + ctx + "\n---\n\n" +
                    "What specific documentation would fill this gap?\n\n" +
                    "Respond with EXACTLY:\n" +
                    "TOPIC:<lowercase-hyphen-name>\n" +
                    "URL:<official documentation URL>\n" +
                    "FOCUS:<what to look for>\n\n" +
                    "Or respond NONE if no external docs needed.";

                String suggestion = llmCallback.apply(prompt);
                if (suggestion == null || suggestion.trim().equalsIgnoreCase("NONE")) return;

                TopicConfig topic = parseSuggestion(suggestion);
                if (topic == null) return;

                // Dedup — don't re-fetch same topic within this stream
                if (triggeredTopics.contains(topic.getName())) return;

                boolean added = scheduler.addTopic(topic);
                if (added) {
                    triggeredTopics.add(topic.getName());
                    System.out.println("\u001b[35m  [Stream Monitor] Pre-fetching: " + topic.getName() + " (reason: " + fetchReason + ")\u001b[0m");
                }
            } catch (Exception e) {
                // Non-fatal — best-effort background enrichment
            }
        });
    }

    private TopicConfig parseSuggestion(String response) {
        if (response == null || response.trim().equalsIgnoreCase("NONE")) return null;

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
        topic.setInstruction(focus != null ? focus : "Fill knowledge gap detected during agent response.");
        topic.setRefreshIntervalMinutes(720); // Low priority — background enrichment
        return topic;
    }
}
