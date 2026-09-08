package com.mkpro.routing;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MarkovRouter uses a learned transition matrix to predict which agent should handle a task.
 * 
 * The matrix encodes: P(next_agent | task_category, last_agent)
 * 
 * When confidence exceeds the threshold, the router can bypass the LLM Coordinator
 * and delegate directly — saving tokens and latency.
 *
 * Matrix is persisted to disk and improves with each training update.
 */
public class MarkovRouter {

    private static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.65;

    // transition[category][lastAgent] = {agent -> count}
    private final Map<String, Map<String, Map<String, Integer>>> transitions = new ConcurrentHashMap<>();
    
    // category -> {agent -> count} (ignoring history, just category → agent mapping)
    private final Map<String, Map<String, Integer>> categoryToAgent = new ConcurrentHashMap<>();

    // Learned patterns from training data (category → set of distinctive tokens/bigrams)
    private Map<String, java.util.Set<String>> learnedPatterns = new ConcurrentHashMap<>();
    
    // Stall patterns: sequences that historically led to escalation (category → list of agent sequences)
    private final Map<String, java.util.List<java.util.List<String>>> stallPatterns = new ConcurrentHashMap<>();
    private final Map<String, Integer> knowledgeStats = new ConcurrentHashMap<>();

    // ═══ Layer 2: Agent → Tool transition probabilities (delegated) ═══
    private final ToolTransitionModel toolModel = new ToolTransitionModel();

    private double confidenceThreshold;
    private int totalObservations = 0;

    public MarkovRouter() {
        this(DEFAULT_CONFIDENCE_THRESHOLD);
    }

    public MarkovRouter(double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
        initSeedPatterns();
    }

    /**
     * Initialize seed learned patterns so fresh installs have baseline routing intelligence.
     * These are overwritten when real training data becomes available.
     */
    private void initSeedPatterns() {
        if (!learnedPatterns.isEmpty()) return; // Don't overwrite loaded patterns

        learnedPatterns.put("CODING", java.util.Set.of(
            "refactor", "implement", "function", "method", "class", "interface",
            "variable", "algorithm", "debug", "compile", "syntax", "code",
            "feature", "module", "component", "logic", "bug fix"
        ));
        learnedPatterns.put("TESTING", java.util.Set.of(
            "test", "junit", "assert", "mock", "coverage", "unit test",
            "integration test", "selenium", "e2e", "regression", "verify"
        ));
        learnedPatterns.put("GIT", java.util.Set.of(
            "commit", "push", "branch", "merge", "rebase", "pull request",
            "stash", "cherry-pick", "tag", "changelog", "diff"
        ));
        learnedPatterns.put("DEVOPS", java.util.Set.of(
            "docker", "kubernetes", "deploy", "pipeline", "ci/cd", "terraform",
            "helm", "nginx", "aws", "gcp", "infrastructure", "container"
        ));
        learnedPatterns.put("ARCHITECTURE", java.util.Set.of(
            "design", "pattern", "microservice", "coupling", "cohesion",
            "architecture", "scalability", "diagram", "refactoring", "solid"
        ));
        learnedPatterns.put("DATABASE", java.util.Set.of(
            "sql", "query", "schema", "migration", "index", "table",
            "postgres", "mysql", "mongodb", "database", "join"
        ));
        learnedPatterns.put("SECURITY", java.util.Set.of(
            "vulnerability", "xss", "injection", "auth", "oauth", "jwt",
            "encrypt", "certificate", "firewall", "audit", "penetration"
        ));
        learnedPatterns.put("DOCUMENTATION", java.util.Set.of(
            "readme", "document", "javadoc", "comment", "wiki",
            "changelog", "api docs", "specification", "markdown"
        ));
        learnedPatterns.put("SYSADMIN", java.util.Set.of(
            "install", "configure", "restart", "service", "process",
            "permission", "cron", "systemd", "package", "environment"
        ));
    }

    /**
     * Result of a routing decision.
     */
    public static class RoutingDecision {
        public final String agent;       // Recommended agent (e.g., "Coder")
        public final double confidence;  // 0.0 to 1.0
        public final boolean shouldRoute; // true if confidence > threshold

        public RoutingDecision(String agent, double confidence, boolean shouldRoute) {
            this.agent = agent;
            this.confidence = confidence;
            this.shouldRoute = shouldRoute;
        }

        @Override
        public String toString() {
            return String.format("%s (%.0f%% confidence, %s)", agent, confidence * 100, 
                shouldRoute ? "ROUTE" : "FALLBACK");
        }
    }

    /**
     * Predict the best agent for a given category and last agent used.
     */
    public RoutingDecision route(IntentClassifier.TaskCategory category, String lastAgent) {
        String catKey = category.name();

        // Try order-1 Markov (category + lastAgent)
        if (lastAgent != null) {
            Map<String, Map<String, Integer>> categoryTransitions = transitions.get(catKey);
            if (categoryTransitions != null) {
                Map<String, Integer> agentCounts = categoryTransitions.get(lastAgent);
                if (agentCounts != null && !agentCounts.isEmpty()) {
                    return pickBest(agentCounts);
                }
            }
        }

        // Fallback: just category → agent (order-0)
        Map<String, Integer> catCounts = categoryToAgent.get(catKey);
        if (catCounts != null && !catCounts.isEmpty()) {
            return pickBest(catCounts);
        }

        // No data — can't route
        return new RoutingDecision("Coordinator", 0.0, false);
    }

    /**
     * Route to the best agent for a category, EXCLUDING agents already tried.
     * Used by Maker to redirect on stall — picks the next-best alternative.
     *
     * @param category The task category
     * @param excludeAgents Agents to exclude (already tried and stuck)
     * @return The best alternative agent, or null if all exhausted
     */
    public String routeExcluding(IntentClassifier.TaskCategory category, java.util.Set<String> excludeAgents) {
        String catKey = category.name();
        Map<String, Integer> catCounts = categoryToAgent.get(catKey);
        if (catCounts == null || catCounts.isEmpty()) return null;

        // Sort by count descending, pick first not in exclude set
        return catCounts.entrySet().stream()
            .filter(e -> !excludeAgents.contains(e.getKey()))
            .filter(e -> !"Coordinator".equals(e.getKey()))
            .max(java.util.Map.Entry.comparingByValue())
            .map(java.util.Map.Entry::getKey)
            .orElse(null);
    }

    /**
     * Record an observed transition (used during training and live learning).
     */
    public void recordTransition(IntentClassifier.TaskCategory category, String lastAgent, String selectedAgent) {
        String catKey = category.name();

        // Update order-1 matrix
        transitions
            .computeIfAbsent(catKey, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(lastAgent != null ? lastAgent : "_START_", k -> new ConcurrentHashMap<>())
            .merge(selectedAgent, 1, Integer::sum);

        // Update order-0 matrix
        categoryToAgent
            .computeIfAbsent(catKey, k -> new ConcurrentHashMap<>())
            .merge(selectedAgent, 1, Integer::sum);

        totalObservations++;
    }

    /**
     * Get the total number of observations the model has seen.
     */
    public int getTotalObservations() {
        return totalObservations;
    }

    /**
     * Set the confidence threshold for direct routing.
     */
    public void setConfidenceThreshold(double threshold) {
        this.confidenceThreshold = threshold;
    }

    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public Map<String, java.util.Set<String>> getLearnedPatterns() {
        return learnedPatterns;
    }

    public void setLearnedPatterns(Map<String, java.util.Set<String>> patterns) {
        if (patterns != null && !patterns.isEmpty()) {
            this.learnedPatterns.clear();
            this.learnedPatterns.putAll(patterns);
        }
        // If patterns is null/empty, keep existing (seed patterns preserved)
    }

    /**
     * Record a stall pattern: the agent sequence that led to escalation.
     */
    public void recordStall(IntentClassifier.TaskCategory category, java.util.List<String> agentSequence) {
        if (agentSequence == null || agentSequence.size() < 2) return;
        stallPatterns
            .computeIfAbsent(category.name(), k -> java.util.Collections.synchronizedList(new java.util.ArrayList<>()))
            .add(new java.util.ArrayList<>(agentSequence));
        // Keep max 50 patterns per category
        var patterns = stallPatterns.get(category.name());
        while (patterns.size() > 50) {
            patterns.remove(0);
        }
    }

    /**
     * Predict probability of stall based on current agent sequence.
     * Matches against stored stall patterns using subsequence overlap.
     * 
     * @return P(stall) between 0.0 and 1.0
     */
    public double predictStall(IntentClassifier.TaskCategory category, java.util.List<String> currentSequence) {
        if (currentSequence == null || currentSequence.size() < 2) return 0.0;
        var patterns = stallPatterns.get(category.name());
        if (patterns == null || patterns.isEmpty()) return 0.0;

        int matches = 0;
        for (var pattern : patterns) {
            if (sequenceOverlap(currentSequence, pattern) >= 0.6) {
                matches++;
            }
        }
        return (double) matches / patterns.size();
    }

    /**
     * Calculate overlap between two agent sequences (0.0 - 1.0).
     * Measures how much of the current sequence matches a known stall pattern.
     */
    private double sequenceOverlap(java.util.List<String> current, java.util.List<String> pattern) {
        if (current.isEmpty() || pattern.isEmpty()) return 0.0;
        
        // Check suffix match: does the end of current look like the start of a stall?
        int matchLen = Math.min(current.size(), pattern.size());
        int matched = 0;
        for (int i = 0; i < matchLen; i++) {
            if (current.get(current.size() - matchLen + i).equals(pattern.get(i))) {
                matched++;
            }
        }
        return (double) matched / matchLen;
    }

    /**
     * Record knowledge acquisition outcome for a goal category.
     * Used by MakerLoop's retrospective analysis to track which categories benefit from knowledge.
     */
    public void recordKnowledgeOutcome(IntentClassifier.TaskCategory category, java.util.List<String> topics, boolean success, int retries) {
        // Store as a lightweight stat: category → success rate with knowledge
        String key = "knowledge:" + category.name();
        knowledgeStats.merge(key + ":total", 1, Integer::sum);
        if (success) knowledgeStats.merge(key + ":success", 1, Integer::sum);
        if (retries > 0) knowledgeStats.merge(key + ":retries", retries, Integer::sum);
    }

    /**
     * Get knowledge effectiveness for a category.
     * @return success rate (0.0–1.0), or -1 if no data
     */
    public double getKnowledgeEffectiveness(IntentClassifier.TaskCategory category) {
        String key = "knowledge:" + category.name();
        int total = knowledgeStats.getOrDefault(key + ":total", 0);
        if (total == 0) return -1.0;
        int successes = knowledgeStats.getOrDefault(key + ":success", 0);
        return (double) successes / total;
    }

    /**
     * Save the transition matrix to disk.
     */
    // ═══ Layer 2: Agent → Tool transition methods ═══

    /**
     * Record tool usage for an agent's turn.
     * Updates both frequency (agent+category → tool) and transitions (agent → lastTool → nextTool).
     *
     * @param agent The agent that used the tools
     * @param category The task category
     * @param toolsUsed Ordered list of tools used in this turn
     */
    public void recordToolUsage(String agent, IntentClassifier.TaskCategory category, List<String> toolsUsed) {
        toolModel.recordToolUsage(agent, category, toolsUsed);
    }

    /**
     * Predict the next tool an agent will use given its last tool.
     * 
     * @return ToolPrediction with tool name and confidence, or null if no data
     */
    public ToolPrediction predictNextTool(String agent, String lastTool) {
        ToolTransitionModel.ToolPrediction result = toolModel.predictNextTool(agent, lastTool);
        if (result == null) return null;
        return new ToolPrediction(result.tool, result.confidence);
    }

    /**
     * Get expected tools for an agent+category, ranked by frequency.
     * Used for stimulus enrichment ("You'll likely need: file_read, file_write, shell").
     *
     * @param agent The agent
     * @param category The task category
     * @param topK Max number of tools to return
     * @return Ordered list of ToolPrediction (highest frequency first)
     */
    public List<ToolPrediction> getExpectedTools(String agent, IntentClassifier.TaskCategory category, int topK) {
        List<ToolTransitionModel.ToolPrediction> results = toolModel.getExpectedTools(agent, category, topK);
        List<ToolPrediction> converted = new ArrayList<>(results.size());
        for (ToolTransitionModel.ToolPrediction tp : results) {
            converted.add(new ToolPrediction(tp.tool, tp.confidence));
        }
        return converted;
    }

    /**
     * Check if an agent's tool usage is anomalous for the given category.
     * Returns true if the tool is not in the agent's typical toolset (< 5% frequency).
     */
    public boolean isAnomalousTool(String agent, IntentClassifier.TaskCategory category, String tool) {
        return toolModel.isAnomalousTool(agent, category, tool);
    }

    /**
     * Tool prediction result.
     * Delegates to {@link ToolTransitionModel.ToolPrediction} — kept here for API compatibility.
     */
    public static class ToolPrediction {
        public final String tool;
        public final double confidence;

        public ToolPrediction(String tool, double confidence) {
            this.tool = tool;
            this.confidence = confidence;
        }

        @Override
        public String toString() {
            return tool + " (" + (int)(confidence * 100) + "%)";
        }
    }

    private static final byte[] MAGIC = "MKPRO_MARKOV".getBytes();
    private static final int MODEL_VERSION = 4; // v1: transitions, v2: patterns, v3: stalls, v4: Layer 2

    /**
     * Save model to disk with corruption protection.
     * Uses atomic write (tmp + rename) and CRC32 checksum.
     */
    public void save(Path path) throws IOException {
        Path tmpPath = path.resolveSibling(path.getFileName() + ".tmp");

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            // Layer 1: Agent transitions
            oos.writeObject(new HashMap<>(transitions));
            oos.writeObject(new HashMap<>(categoryToAgent));
            oos.writeInt(totalObservations);

            // v2: learned patterns
            HashMap<String, java.util.Set<String>> serializablePatterns = new HashMap<>();
            for (var e : learnedPatterns.entrySet()) {
                serializablePatterns.put(e.getKey(), new java.util.HashSet<>(e.getValue()));
            }
            oos.writeObject(serializablePatterns);

            // v3: stall patterns
            HashMap<String, java.util.List<java.util.List<String>>> serializableStalls = new HashMap<>();
            for (var e : stallPatterns.entrySet()) {
                serializableStalls.put(e.getKey(), new java.util.ArrayList<>(e.getValue()));
            }
            oos.writeObject(serializableStalls);

            // v4: Layer 2 — tool transitions and frequencies
            HashMap<String, Map<String, Map<String, Integer>>> serializableToolTrans = new HashMap<>();
            for (var e : toolModel.getToolTransitions().entrySet()) {
                HashMap<String, Map<String, Integer>> inner = new HashMap<>();
                for (var e2 : e.getValue().entrySet()) {
                    inner.put(e2.getKey(), new HashMap<>(e2.getValue()));
                }
                serializableToolTrans.put(e.getKey(), inner);
            }
            oos.writeObject(serializableToolTrans);

            HashMap<String, Map<String, Integer>> serializableToolFreq = new HashMap<>();
            for (var e : toolModel.getAgentToolFrequency().entrySet()) {
                serializableToolFreq.put(e.getKey(), new HashMap<>(e.getValue()));
            }
            oos.writeObject(serializableToolFreq);
        }

        byte[] data = baos.toByteArray();

        // Compute CRC32 checksum
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        long checksum = crc.getValue();

        // Write: MAGIC + VERSION + DATA_LENGTH + DATA + CHECKSUM
        try (java.io.DataOutputStream dos = new java.io.DataOutputStream(Files.newOutputStream(tmpPath))) {
            dos.write(MAGIC);
            dos.writeInt(MODEL_VERSION);
            dos.writeInt(data.length);
            dos.write(data);
            dos.writeLong(checksum);
        }

        // Backup existing model before overwriting
        if (Files.exists(path)) {
            Path backupPath = path.resolveSibling(path.getFileName() + ".bak");
            try { Files.copy(path, backupPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING); } catch (Exception e) { /* best-effort */ }
        }

        // Atomic rename: tmp → target
        Files.move(tmpPath, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Load model from disk with corruption detection.
     * Validates magic header, version, and CRC32 checksum.
     * If corrupt, falls back to backup or starts fresh.
     */
    @SuppressWarnings("unchecked")
    public void load(Path path) throws IOException, ClassNotFoundException {
        if (!Files.exists(path)) return;

        byte[] fileBytes = Files.readAllBytes(path);

        // Validate magic header
        if (fileBytes.length < MAGIC.length + 4 + 4 + 8) {
            System.err.println("[MarkovRouter] Model file too small, attempting backup...");
            loadBackup(path);
            return;
        }

        for (int i = 0; i < MAGIC.length; i++) {
            if (fileBytes[i] != MAGIC[i]) {
                // Not a v4+ model — try legacy load
                loadLegacy(path);
                return;
            }
        }

        java.io.DataInputStream dis = new java.io.DataInputStream(new java.io.ByteArrayInputStream(fileBytes));
        dis.skipBytes(MAGIC.length); // skip magic
        int version = dis.readInt();
        int dataLength = dis.readInt();

        if (dataLength < 0 || MAGIC.length + 4 + 4 + dataLength + 8 > fileBytes.length) {
            System.err.println("[MarkovRouter] Model file corrupt (bad data length), attempting backup...");
            loadBackup(path);
            return;
        }

        byte[] data = new byte[dataLength];
        dis.readFully(data);
        long storedChecksum = dis.readLong();

        // Validate checksum
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        if (crc.getValue() != storedChecksum) {
            System.err.println("[MarkovRouter] Model file checksum mismatch, attempting backup...");
            loadBackup(path);
            return;
        }

        // Deserialize data
        try (ObjectInputStream ois = new ObjectInputStream(new java.io.ByteArrayInputStream(data))) {
            // Layer 1
            Map<String, Map<String, Map<String, Integer>>> loaded = (Map<String, Map<String, Map<String, Integer>>>) ois.readObject();
            transitions.putAll(loaded);
            Map<String, Map<String, Integer>> loadedCat = (Map<String, Map<String, Integer>>) ois.readObject();
            categoryToAgent.putAll(loadedCat);
            totalObservations = ois.readInt();

            // v2: learned patterns
            if (version >= 2) {
                try {
                    Map<String, java.util.Set<String>> loadedPatterns = (Map<String, java.util.Set<String>>) ois.readObject();
                    if (loadedPatterns != null) learnedPatterns.putAll(loadedPatterns);
                } catch (Exception e) { /* OK */ }
            }

            // v3: stall patterns
            if (version >= 3) {
                try {
                    Map<String, java.util.List<java.util.List<String>>> loadedStalls = (Map<String, java.util.List<java.util.List<String>>>) ois.readObject();
                    if (loadedStalls != null) stallPatterns.putAll(loadedStalls);
                } catch (Exception e) { /* OK */ }
            }

            // v4: Layer 2 — tool transitions and frequencies
            if (version >= 4) {
                try {
                    Map<String, Map<String, Map<String, Integer>>> loadedToolTrans = (Map<String, Map<String, Map<String, Integer>>>) ois.readObject();
                    if (loadedToolTrans != null) {
                        toolModel.setToolTransitions(loadedToolTrans);
                    }
                    Map<String, Map<String, Integer>> loadedToolFreq = (Map<String, Map<String, Integer>>) ois.readObject();
                    if (loadedToolFreq != null) {
                        toolModel.setAgentToolFrequency(loadedToolFreq);
                    }
                } catch (Exception e) { /* OK — v3 model without Layer 2 */ }
            }
        }
    }

    /**
     * Load from backup file (.bak) if primary is corrupt.
     */
    private void loadBackup(Path primaryPath) {
        Path backupPath = primaryPath.resolveSibling(primaryPath.getFileName() + ".bak");
        if (Files.exists(backupPath)) {
            System.err.println("[MarkovRouter] Loading from backup: " + backupPath);
            try {
                // Copy backup to primary (will be re-saved with new format on exit)
                Files.copy(backupPath, primaryPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                loadLegacy(primaryPath);
            } catch (Exception e) {
                System.err.println("[MarkovRouter] Backup also corrupt. Starting fresh.");
            }
        } else {
            System.err.println("[MarkovRouter] No backup available. Starting fresh.");
        }
    }

    /**
     * Legacy load (pre-v4 format without magic header).
     */
    @SuppressWarnings("unchecked")
    private void loadLegacy(Path path) throws IOException {
        try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(path))) {
            Map<String, Map<String, Map<String, Integer>>> loaded = (Map<String, Map<String, Map<String, Integer>>>) ois.readObject();
            transitions.putAll(loaded);
            Map<String, Map<String, Integer>> loadedCat = (Map<String, Map<String, Integer>>) ois.readObject();
            categoryToAgent.putAll(loadedCat);
            totalObservations = ois.readInt();
            try {
                Map<String, java.util.Set<String>> loadedPatterns = (Map<String, java.util.Set<String>>) ois.readObject();
                if (loadedPatterns != null) learnedPatterns.putAll(loadedPatterns);
            } catch (Exception e) { /* OK */ }
            try {
                Map<String, java.util.List<java.util.List<String>>> loadedStalls = (Map<String, java.util.List<java.util.List<String>>>) ois.readObject();
                if (loadedStalls != null) stallPatterns.putAll(loadedStalls);
            } catch (Exception e) { /* OK */ }
        } catch (ClassNotFoundException e) {
            System.err.println("[MarkovRouter] Legacy model class not found: " + e.getMessage());
        }
    }

    /**
     * Pick the best agent from a count map with confidence scoring.
     */
    private RoutingDecision pickBest(Map<String, Integer> agentCounts) {
        int total = agentCounts.values().stream().mapToInt(Integer::intValue).sum();
        String bestAgent = null;
        int bestCount = 0;

        for (Map.Entry<String, Integer> entry : agentCounts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                bestAgent = entry.getKey();
            }
        }

        if (bestAgent == null) {
            return new RoutingDecision("Coordinator", 0.0, false);
        }

        double confidence = (double) bestCount / total;
        return new RoutingDecision(bestAgent, confidence, confidence >= confidenceThreshold);
    }

    /**
     * Get a snapshot of the matrix for debugging/display.
     */
    public Map<String, Map<String, Integer>> getCategoryToAgentMatrix() {
        return Collections.unmodifiableMap(categoryToAgent);
    }

    /**
     * Get the full transition matrix: category → lastAgent → {nextAgent → count}
     */
    public Map<String, Map<String, Map<String, Integer>>> getTransitionMatrix() {
        return Collections.unmodifiableMap(transitions);
    }

    /**
     * Get the agent→tool frequency map for display (Layer 2 stats).
     */
    public Map<String, Map<String, Integer>> getAgentToolFrequency() {
        return toolModel.getAgentToolFrequency();
    }

    // ==========================================================================
    // MAKER SUPPORT — Sequence completion and stall detection
    // ==========================================================================

    // completionPatterns[category][toolSequenceHash] = {COMPLETE: n, INCOMPLETE: m}
    private final Map<String, Map<String, Map<String, Integer>>> completionPatterns = new ConcurrentHashMap<>();
    
    // turnsToComplete[category] = [totalTurns, completedGoals] for running average
    private final Map<String, long[]> turnsToComplete = new ConcurrentHashMap<>();
    
    private double stallMultiplier = 2.0;

    public enum MakerAction {
        CONTINUE,   // Keep going, inject stimulus for next step
        RETRY,      // Last step failed, re-route
        ESCALATE,   // Stalled or uncertain, ask user
        COMPLETE    // Verified done, mark goal complete
    }

    /**
     * Predict completion probability based on tools invoked so far.
     * @return 0.0 (not done) to 1.0 (definitely complete)
     */
    public double predictCompletion(IntentClassifier.TaskCategory category, List<String> toolSequence) {
        String catKey = category.name();
        String seqHash = hashToolSequence(toolSequence);

        Map<String, Map<String, Integer>> catPatterns = completionPatterns.get(catKey);
        if (catPatterns == null || catPatterns.isEmpty()) {
            return 0.0; // No data for this category
        }

        Map<String, Integer> counts = catPatterns.get(seqHash);
        if (counts == null) {
            // Try prefix matching — check if any known pattern is a superset
            return estimateFromPartialMatch(catPatterns, toolSequence);
        }

        int complete = counts.getOrDefault("COMPLETE", 0);
        int incomplete = counts.getOrDefault("INCOMPLETE", 0);
        int total = complete + incomplete;
        if (total == 0) return 0.0;

        return (double) complete / total;
    }

    /**
     * Record a completed/failed goal sequence for learning.
     */
    public void recordCompletion(IntentClassifier.TaskCategory category, List<String> toolSequence, 
                                  boolean success, int turns) {
        String catKey = category.name();
        String seqHash = hashToolSequence(toolSequence);

        completionPatterns
            .computeIfAbsent(catKey, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(seqHash, k -> new ConcurrentHashMap<>())
            .merge(success ? "COMPLETE" : "INCOMPLETE", 1, Integer::sum);

        // Update turns average
        if (success) {
            turnsToComplete.compute(catKey, (k, v) -> {
                if (v == null) v = new long[]{0, 0};
                v[0] += turns;
                v[1]++;
                return v;
            });
        }
    }

    /**
     * Check if current turn count indicates a stall.
     */
    public boolean isStalled(IntentClassifier.TaskCategory category, int currentTurns) {
        String catKey = category.name();
        long[] avg = turnsToComplete.get(catKey);
        if (avg == null || avg[1] == 0) {
            // No data — use default of 6 turns as stall threshold
            return currentTurns > 6;
        }
        double avgTurns = (double) avg[0] / avg[1];
        return currentTurns > (avgTurns * stallMultiplier);
    }

    /**
     * Get average turns to complete for a category.
     */
    public int getAvgTurns(IntentClassifier.TaskCategory category) {
        long[] avg = turnsToComplete.get(category.name());
        if (avg == null || avg[1] == 0) return 5; // Default
        return (int) (avg[0] / avg[1]);
    }

    /**
     * Recommend the next action for the Maker based on current state.
     */
    public MakerAction recommendAction(IntentClassifier.TaskCategory category, int turns,
                                         String lastAgent, boolean lastSuccess, List<String> toolsUsed) {
        // Check completion first
        double completionProb = predictCompletion(category, toolsUsed);
        if (completionProb >= 0.75) {
            return MakerAction.COMPLETE;
        }

        // Check stall
        if (isStalled(category, turns)) {
            return MakerAction.ESCALATE;
        }

        // Check failure
        if (!lastSuccess) {
            return MakerAction.RETRY;
        }

        // Keep going
        return MakerAction.CONTINUE;
    }

    public void setStallMultiplier(double multiplier) {
        this.stallMultiplier = multiplier;
    }

    private String hashToolSequence(List<String> tools) {
        if (tools == null || tools.isEmpty()) return "EMPTY";
        List<String> sorted = new ArrayList<>(tools);
        Collections.sort(sorted);
        return String.join("|", sorted);
    }

    private double estimateFromPartialMatch(Map<String, Map<String, Integer>> catPatterns, List<String> toolSequence) {
        // Check if any known complete pattern is a subset of what we have
        Set<String> currentTools = new java.util.HashSet<>(toolSequence);
        double bestMatch = 0.0;

        for (Map.Entry<String, Map<String, Integer>> entry : catPatterns.entrySet()) {
            String knownHash = entry.getKey();
            Set<String> knownTools = new java.util.HashSet<>(Arrays.asList(knownHash.split("\\|")));
            
            // What fraction of the known completion pattern have we covered?
            long covered = knownTools.stream().filter(currentTools::contains).count();
            double coverage = knownTools.isEmpty() ? 0 : (double) covered / knownTools.size();
            
            int complete = entry.getValue().getOrDefault("COMPLETE", 0);
            int total = complete + entry.getValue().getOrDefault("INCOMPLETE", 0);
            double patternConfidence = total > 0 ? (double) complete / total : 0;

            double score = coverage * patternConfidence;
            bestMatch = Math.max(bestMatch, score);
        }

        return bestMatch;
    }
}
