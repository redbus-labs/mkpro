package com.mkpro.knowledge;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Main scheduler that drives the knowledge accumulation loop.
 * Periodically refreshes topics by fetching sources, running LLM analysis,
 * and storing updated reports.
 */
public class KnowledgeScheduler {

    private static final String LOG_PREFIX = "[Knowledge] ";
    private static final int STAGGER_DELAY_SECONDS = 30;
    private static final int TOP_KEYWORDS_COUNT = 20;
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
            "of", "with", "by", "from", "is", "are", "was", "were", "be", "been",
            "being", "have", "has", "had", "do", "does", "did", "will", "would",
            "could", "should", "may", "might", "shall", "can", "need", "must",
            "it", "its", "this", "that", "these", "those", "i", "you", "he", "she",
            "we", "they", "me", "him", "her", "us", "them", "my", "your", "his",
            "our", "their", "what", "which", "who", "whom", "when", "where", "why",
            "how", "all", "each", "every", "both", "few", "more", "most", "other",
            "some", "such", "no", "not", "only", "same", "so", "than", "too", "very",
            "just", "because", "as", "if", "then", "else", "about", "up", "out",
            "into", "through", "during", "before", "after", "above", "below",
            "between", "under", "again", "further", "once", "here", "there", "also"
    );

    private final KnowledgeStore store;
    private final TopicIndex index;
    private final SourceFetcher fetcher;
    private final List<TopicConfig> topics;
    private final ScheduledExecutorService executor;
    private final Map<String, TopicConfig> topicsByName;

    private volatile BiFunction<String, String, String> analyzeCallback;

    public KnowledgeScheduler(KnowledgeStore store, TopicIndex index, SourceFetcher fetcher, List<TopicConfig> topics) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.index = Objects.requireNonNull(index, "index must not be null");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher must not be null");
        this.topics = new CopyOnWriteArrayList<>(Objects.requireNonNull(topics, "topics must not be null"));
        this.topicsByName = new ConcurrentHashMap<>();
        for (TopicConfig topic : topics) {
            topicsByName.put(topic.getName(), topic);
        }

        ThreadFactory daemonFactory = r -> {
            Thread t = new Thread(r, "knowledge-scheduler");
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newSingleThreadScheduledExecutor(daemonFactory);
    }

    /**
     * Starts the scheduler, scheduling each topic at its configured refresh interval
     * with staggered initial delays (topic_index * 30 seconds).
     */
    public void start() {
        log("Starting scheduler with " + topics.size() + " topics");
        for (int i = 0; i < topics.size(); i++) {
            TopicConfig topic = topics.get(i);
            long initialDelay = (long) i * STAGGER_DELAY_SECONDS;
            long interval = topic.getRefreshIntervalMinutes();

            executor.scheduleAtFixedRate(
                    () -> safeRefresh(topic),
                    initialDelay,
                    TimeUnit.MINUTES.toSeconds(interval),
                    TimeUnit.SECONDS
            );

            log("Scheduled topic '" + topic.getName() + "' every " + interval
                    + " min (initial delay: " + initialDelay + "s)");
        }

        // Schedule daily staleness decay (every 24 hours)
        executor.scheduleAtFixedRate(
                this::applyStaleDecay,
                TimeUnit.HOURS.toSeconds(1), // first run after 1 hour
                TimeUnit.HOURS.toSeconds(24),
                TimeUnit.SECONDS
        );
    }

    /**
     * Gracefully shuts down the executor, waiting up to 30 seconds for tasks to complete.
     */
    public void stop() {
        log("Stopping scheduler...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                log("Forced shutdown after timeout");
            } else {
                log("Scheduler stopped gracefully");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            log("Scheduler interrupted during shutdown");
        }
    }

    /**
     * Core refresh loop for a single topic.
     * Fetches sources, builds analysis prompt, invokes LLM callback,
     * updates the report, and reindexes.
     */
    public void refreshTopic(TopicConfig topic) {
        String topicName = topic.getName();
        log("Refreshing topic: " + topicName);

        // a. Fetch all sources for the topic
        Map<String, String> fetched = fetcher.fetchAll(topic.getSources());
        if (fetched == null || fetched.isEmpty()) {
            log("No source data fetched for topic: " + topicName);
            return;
        }

        String newData = String.join("\n---\n", fetched.values());

        // b. Get existing report or create new
        TopicReport report = store.getReport(topicName);
        if (report == null) {
            report = new TopicReport(topicName, topic.getTitle() != null ? topic.getTitle() : topicName);
        }

        // c. Build analysis prompt
        String prompt = buildAnalysisPrompt(topic, report, newData);

        // d. Call analyzeCallback
        BiFunction<String, String, String> callback = this.analyzeCallback;
        if (callback == null) {
            log("No analyze callback set, skipping analysis for: " + topicName);
            return;
        }

        String analysisResult = callback.apply(topicName, prompt);
        if (analysisResult == null || analysisResult.isBlank()) {
            log("Empty analysis result for topic: " + topicName);
            return;
        }

        // e. Parse discovered topics from analysis result
        String cleanedResult = parseAndStoreDiscoveries(topicName, analysisResult);

        // f. Update report with intelligent merging metadata
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        // Confidence update: increases if we got meaningful new analysis
        double oldConfidence = report.getConfidence();
        double newConfidence = Math.min(1.0, oldConfidence + 0.05); // +5% per successful refresh
        if (report.getSummary() != null && !report.getSummary().isBlank()) {
            // If existing summary existed and new result is substantially different, higher confidence
            double similarity = computeSimpleSimilarity(report.getSummary(), cleanedResult);
            if (similarity < 0.3) {
                // Very different content — maybe contradictory, lower confidence slightly
                newConfidence = Math.max(0.3, oldConfidence - 0.05);
            } else if (similarity > 0.8) {
                // Very similar — confirms existing, boost confidence
                newConfidence = Math.min(1.0, oldConfidence + 0.1);
            }
        }

        report.setSummary(cleanedResult);
        report.setLastUpdated(now);
        report.setConfidence(newConfidence);
        report.getHistory().add(new TopicReport.HistoryEntry(now, "Updated from " + fetched.size() + " sources (confidence: " + String.format("%.0f%%", newConfidence * 100) + ")"));
        report.setKeywords(extractKeywords(cleanedResult));
        report.setSources(new ArrayList<>(topic.getSources()));

        // g. Save to KnowledgeStore
        store.saveReport(report);
        log("Saved report for topic: " + topicName + " (confidence: " + String.format("%.0f%%", newConfidence * 100) + ")");

        // h. Reindex in TopicIndex
        index.indexTopic(topicName, cleanedResult);
        index.rebuildIdf();
        log("Reindexed topic: " + topicName);
    }

    /**
     * Sets the callback function that performs LLM analysis.
     * Takes (topicName, prompt) and returns the analysis result string.
     */
    public void setAnalyzeCallback(BiFunction<String, String, String> callback) {
        this.analyzeCallback = callback;
    }

    /**
     * Triggers an immediate refresh of a specific topic by name.
     */
    public void forceRefresh(String topicName) {
        TopicConfig topic = topicsByName.get(topicName);
        if (topic == null) {
            log("Unknown topic for force refresh: " + topicName);
            return;
        }
        log("Force refresh requested for: " + topicName);
        executor.submit(() -> safeRefresh(topic));
    }

    /**
     * Returns a map of topic name → last updated timestamp for all topics.
     */
    public Map<String, String> getStatus() {
        Map<String, String> status = new LinkedHashMap<>();
        for (TopicConfig topic : topics) {
            TopicReport report = store.getReport(topic.getName());
            String lastUpdated = (report != null && report.getLastUpdated() != null)
                    ? report.getLastUpdated()
                    : "never";
            status.put(topic.getName(), lastUpdated);
        }
        return status;
    }

    /**
     * Wraps refreshTopic with error handling so one topic failure
     * doesn't crash the scheduler or affect other topics.
     */
    private void safeRefresh(TopicConfig topic) {
        try {
            refreshTopic(topic);
        } catch (Exception e) {
            log("ERROR refreshing topic '" + topic.getName() + "': " + e.getMessage());
        }
    }

    /**
     * Builds the analysis prompt that includes existing summary, new data, and topic instruction.
     */
    private String buildAnalysisPrompt(TopicConfig topic, TopicReport existingReport, String newData) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("You are a knowledge analyst maintaining a living report on a topic.\n\n");
        prompt.append("## Topic: ").append(topic.getName()).append("\n");
        if (topic.getTitle() != null) {
            prompt.append("## Title: ").append(topic.getTitle()).append("\n");
        }
        prompt.append("\n");

        if (topic.getInstruction() != null && !topic.getInstruction().isBlank()) {
            prompt.append("## Your Focus:\n").append(topic.getInstruction()).append("\n\n");
        }

        if (existingReport.getSummary() != null && !existingReport.getSummary().isBlank()) {
            prompt.append("## Existing Report (EVOLVE this, do NOT discard):\n");
            prompt.append(existingReport.getSummary()).append("\n\n");
        } else {
            prompt.append("## Note: This is the FIRST analysis for this topic. Create a comprehensive initial report.\n\n");
        }

        prompt.append("## Newly Fetched Data:\n");
        // Truncate new data to prevent token overflow
        String truncatedData = newData.length() > 8000 ? newData.substring(0, 8000) + "\n...[truncated]" : newData;
        prompt.append(truncatedData).append("\n\n");

        prompt.append("## Instructions:\n");
        prompt.append("1. MERGE new information into the existing report. Do NOT simply replace it.\n");
        prompt.append("2. If new data contradicts existing knowledge, note the contradiction and which is more recent.\n");
        prompt.append("3. Identify trends, patterns, or significant changes from the previous version.\n");
        prompt.append("4. Keep the report concise but comprehensive (max 1500 words).\n");
        prompt.append("5. If you discover related sub-topics worth tracking separately, append them at the end as:\n");
        prompt.append("   DISCOVER_TOPIC:<topic-name>:<suggested-url-or-description>\n");
        prompt.append("   (one per line, only if genuinely useful)\n\n");
        prompt.append("Produce ONLY the updated report text (and optional DISCOVER_TOPIC lines at the very end).");

        return prompt.toString();
    }

    /**
     * Extracts top 20 most frequent non-stopword tokens from the given text.
     * Simple tokenize-and-frequency approach.
     */
    private List<String> extractKeywords(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        // Tokenize: split on non-alphanumeric, lowercase, filter short/stopwords
        String[] tokens = text.toLowerCase().split("[^a-zA-Z0-9]+");

        Map<String, Integer> frequency = new HashMap<>();
        for (String token : tokens) {
            if (token.length() < 3) continue;
            if (STOPWORDS.contains(token)) continue;
            frequency.merge(token, 1, Integer::sum);
        }

        return frequency.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(TOP_KEYWORDS_COUNT)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private void log(String message) {
        System.out.println(LOG_PREFIX + message);
    }

    // ========================================================================
    // Phase 2: Topic Discovery
    // ========================================================================

    /** Discovered topics pending user approval */
    private final List<DiscoveredTopic> pendingDiscoveries = new CopyOnWriteArrayList<>();

    /**
     * Parse DISCOVER_TOPIC lines from analysis result, store as pending discoveries,
     * and return the cleaned result (without discovery lines).
     */
    private String parseAndStoreDiscoveries(String parentTopic, String analysisResult) {
        StringBuilder cleaned = new StringBuilder();
        String[] lines = analysisResult.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("DISCOVER_TOPIC:")) {
                String[] parts = trimmed.substring("DISCOVER_TOPIC:".length()).split(":", 2);
                if (parts.length >= 1 && !parts[0].isBlank()) {
                    String name = parts[0].trim().toLowerCase().replaceAll("[^a-z0-9-]", "-");
                    String description = parts.length > 1 ? parts[1].trim() : "";
                    // Don't add duplicates
                    boolean exists = pendingDiscoveries.stream()
                        .anyMatch(d -> d.name.equals(name));
                    if (!exists && !topicsByName.containsKey(name)) {
                        pendingDiscoveries.add(new DiscoveredTopic(name, description, parentTopic));
                        log("Discovered new topic: " + name + " (from " + parentTopic + ")");
                    }
                }
            } else {
                cleaned.append(line).append("\n");
            }
        }

        return cleaned.toString().trim();
    }

    /** Get pending discovered topics */
    public List<DiscoveredTopic> getPendingDiscoveries() {
        return Collections.unmodifiableList(pendingDiscoveries);
    }

    /**
     * Add a topic requested by an agent. Auto-approved, priority boosted.
     * This skips the pending discoveries queue and immediately schedules.
     */
    public void addAgentRequestedTopic(String topicName, String description, List<String> sources) {
        // Dedup check
        if (topicsByName.containsKey(topicName)) {
            log("Topic '" + topicName + "' already scheduled, skipping agent request.");
            return;
        }

        TopicConfig newTopic = new TopicConfig();
        newTopic.setName(topicName);
        newTopic.setTitle(topicName);
        newTopic.setInstruction("Agent knowledge gap: " + description);
        newTopic.setSources(sources != null && !sources.isEmpty() ? sources : Collections.emptyList());
        // Priority boost: 50% of default interval
        newTopic.setRefreshIntervalMinutes(30); // Aggressive initial refresh

        topics.add(newTopic);
        topicsByName.put(topicName, newTopic);

        log("Agent-requested topic added: " + topicName + " (auto-approved, priority boost)");

        // Schedule immediate refresh
        executor.schedule(() -> safeRefresh(newTopic), 5, TimeUnit.SECONDS);
    }

    /** Approve a discovered topic — adds it to the scheduler */
    public void approveDiscovery(String name) {
        DiscoveredTopic discovery = pendingDiscoveries.stream()
            .filter(d -> d.name.equals(name))
            .findFirst().orElse(null);

        if (discovery == null) {
            log("Discovery not found: " + name);
            return;
        }

        TopicConfig newTopic = new TopicConfig();
        newTopic.setName(discovery.name);
        newTopic.setTitle(discovery.name);
        newTopic.setInstruction("Explore and summarize: " + discovery.description);
        newTopic.setSources(discovery.description.startsWith("http") 
            ? List.of(discovery.description) 
            : Collections.emptyList());
        newTopic.setRefreshIntervalMinutes(120);

        topics.add(newTopic);
        topicsByName.put(discovery.name, newTopic);
        pendingDiscoveries.remove(discovery);

        log("Approved topic: " + name);
        // Schedule it
        executor.schedule(() -> safeRefresh(newTopic), 10, TimeUnit.SECONDS);
    }

    /** Dismiss a discovered topic */
    public void dismissDiscovery(String name) {
        pendingDiscoveries.removeIf(d -> d.name.equals(name));
    }

    public static class DiscoveredTopic {
        public final String name;
        public final String description;
        public final String discoveredFrom;
        public final String discoveredAt;

        public DiscoveredTopic(String name, String description, String discoveredFrom) {
            this.name = name;
            this.description = description;
            this.discoveredFrom = discoveredFrom;
            this.discoveredAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
    }

    // ========================================================================
    // Phase 2: Confidence & Staleness
    // ========================================================================

    /**
     * Compute simple Jaccard-like similarity between two texts.
     * Used to detect how much new analysis differs from existing.
     */
    private double computeSimpleSimilarity(String text1, String text2) {
        if (text1 == null || text2 == null) return 0.0;

        Set<String> tokens1 = tokenize(text1);
        Set<String> tokens2 = tokenize(text2);

        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(tokens1);
        intersection.retainAll(tokens2);

        Set<String> union = new HashSet<>(tokens1);
        union.addAll(tokens2);

        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        for (String t : text.toLowerCase().split("[^a-zA-Z0-9]+")) {
            if (t.length() >= 3 && !STOPWORDS.contains(t)) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    /**
     * Apply staleness decay to all topic reports.
     * Called periodically — reduces confidence for topics not refreshed recently.
     * Decay: -1% per day since last update.
     */
    public void applyStaleDecay() {
        for (TopicConfig topic : topics) {
            TopicReport report = store.getReport(topic.getName());
            if (report == null || report.getLastUpdated() == null) continue;

            try {
                LocalDateTime lastUpdated = LocalDateTime.parse(report.getLastUpdated(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                long daysSinceUpdate = java.time.Duration.between(lastUpdated, LocalDateTime.now()).toDays();

                if (daysSinceUpdate > 1) {
                    double decayedConfidence = Math.max(0.1, report.getConfidence() - (daysSinceUpdate * 0.01));
                    if (decayedConfidence < report.getConfidence()) {
                        report.setConfidence(decayedConfidence);
                        store.saveReport(report);
                    }
                }
            } catch (Exception e) {
                // Ignore parse errors
            }
        }
    }

    // ========================================================================
    // Phase 2: Access-Frequency Weighted Refresh
    // ========================================================================

    /** Track search hits per topic */
    private final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> accessCounts = new ConcurrentHashMap<>();

    /** Record that a topic was accessed (searched for) */
    public void recordAccess(String topicName) {
        accessCounts.computeIfAbsent(topicName, k -> new java.util.concurrent.atomic.AtomicInteger(0)).incrementAndGet();
    }

    /** Get access count for a topic */
    public int getAccessCount(String topicName) {
        var counter = accessCounts.get(topicName);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Compute effective refresh interval for a topic based on access frequency.
     * More accessed topics refresh more frequently (min 50% of configured interval).
     */
    public int getEffectiveInterval(TopicConfig topic) {
        int baseInterval = topic.getRefreshIntervalMinutes();
        int accesses = getAccessCount(topic.getName());

        if (accesses == 0) return baseInterval;

        // Each 5 accesses reduces interval by 10%, min 50% of base
        double reduction = Math.min(0.5, accesses * 0.02);
        return Math.max(baseInterval / 2, (int) (baseInterval * (1.0 - reduction)));
    }

    /** Get access frequency map (for /know status display) */
    public Map<String, Integer> getAccessFrequencies() {
        Map<String, Integer> result = new LinkedHashMap<>();
        accessCounts.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }
}
