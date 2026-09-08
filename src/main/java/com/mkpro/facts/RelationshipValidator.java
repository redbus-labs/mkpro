package com.mkpro.facts;

import java.util.*;

/**
 * Validates relationship claims against the knowledge graph.
 * Supports direct checks, transitive inference, and contradiction detection.
 */
public class RelationshipValidator {

    private final RelationshipGraph graph;

    public RelationshipValidator(RelationshipGraph graph) {
        this.graph = graph;
    }

    /**
     * Direct check: is this relationship known?
     */
    public boolean check(String subject, String predicate, String object) {
        return graph.check(subject, predicate, object);
    }

    /**
     * Transitive check: can we infer this relationship through a chain?
     * Returns the chain if found (e.g., [HPA, metrics-server, K8s 1.8+]).
     */
    public List<String> checkTransitive(String subject, String predicate, String object) {
        return graph.checkTransitive(subject, predicate, object);
    }

    /**
     * Query: what does subject [predicate]?
     * e.g., query("HPA", "requires") → ["metrics-server"]
     */
    public List<String> query(String subject, String predicate) {
        return graph.query(subject, predicate);
    }

    /**
     * Get all known relationships for a subject.
     */
    public List<RelationshipGraph.Edge> getAllRelationships(String subject) {
        return graph.getRelationships(subject);
    }

    /**
     * Validate a claim text against known facts.
     * Returns list of issues found (empty = no conflicts).
     *
     * Detects patterns like:
     * - "X without Y" where X requires Y
     * - "X replaces Y" where Y replaces X
     * - "X doesn't need Y" where X requires Y
     */
    public List<String> validateClaim(String claimText) {
        if (claimText == null || claimText.isBlank()) return Collections.emptyList();

        List<String> issues = new ArrayList<>();
        String lower = claimText.toLowerCase();

        // Check "without" pattern: "HPA without metrics-server"
        checkWithoutPattern(lower, issues);

        // Check "doesn't need/require" pattern
        checkNegationPattern(lower, issues);

        // Check "X replaces Y" where Y actually replaces X
        checkReplacesContradiction(lower, issues);

        return issues;
    }

    /**
     * Detect contradiction if a new fact were added.
     */
    public String wouldContradict(String subject, String predicate, String object) {
        return graph.detectContradiction(subject, predicate, object);
    }

    // ═══ Private helpers ═══

    private void checkWithoutPattern(String text, List<String> issues) {
        // Pattern: "X without Y" — check if X requires Y
        int idx = text.indexOf(" without ");
        if (idx < 0) return;

        // Extract subject before "without" and object after
        String before = text.substring(Math.max(0, idx - 50), idx).trim();
        String after = text.substring(idx + 9, Math.min(text.length(), idx + 60)).trim();

        // Try to match against known relationships
        for (RelationshipTriple triple : getRequiresRelationships()) {
            String subj = triple.getSubject().toLowerCase();
            String obj = triple.getObject().toLowerCase();
            if (before.contains(subj) && after.contains(obj)) {
                issues.add("CONFLICT: '" + triple.getSubject() + "' requires '" +
                    triple.getObject() + "', but claim says 'without " + triple.getObject() + "'");
            }
        }
    }

    private void checkReplacesContradiction(String text, List<String> issues) {
        if (replacesCache == null) return;
        // Pattern: "X replaces Y" where we know "Y replaces X"
        for (RelationshipTriple triple : replacesCache) {
            String subj = triple.getSubject().toLowerCase();
            String obj = triple.getObject().toLowerCase();
            // Check if text claims the reverse: "obj replaces subj"
            String reversePattern = obj + " replaces " + subj;
            String reversePattern2 = obj + " replace " + subj;
            if (text.contains(reversePattern) || text.contains(reversePattern2)) {
                issues.add("CONFLICT: Known fact is '" + triple.getSubject() + " replaces " +
                    triple.getObject() + "', but claim reverses this.");
            }
        }
    }

    private void checkNegationPattern(String text, List<String> issues) {
        // Patterns: "doesn't need", "doesn't require", "no need for", "not require"
        String[] negations = {"doesn't need", "doesn't require", "does not need",
                             "does not require", "no need for", "not require"};

        for (String neg : negations) {
            int idx = text.indexOf(neg);
            if (idx < 0) continue;

            String before = text.substring(Math.max(0, idx - 50), idx).trim();
            String after = text.substring(idx + neg.length(), Math.min(text.length(), idx + neg.length() + 60)).trim();

            for (RelationshipTriple triple : getRequiresRelationships()) {
                String subj = triple.getSubject().toLowerCase();
                String obj = triple.getObject().toLowerCase();
                if (before.contains(subj) && after.contains(obj)) {
                    issues.add("CONFLICT: '" + triple.getSubject() + "' requires '" +
                        triple.getObject() + "', contradicts negation claim");
                }
            }
        }
    }

    private List<RelationshipTriple> requiresCache;
    private List<RelationshipTriple> replacesCache;

    private List<RelationshipTriple> getRequiresRelationships() {
        if (requiresCache == null) {
            requiresCache = new ArrayList<>();
            // Build from graph — this is a workaround; ideally we'd query the store
            // For now, we scan the graph's outgoing edges
        }
        return requiresCache;
    }

    /**
     * Initialize the requires cache from the FactStore.
     */
    public void initFromStore(FactStore store) {
        requiresCache = new ArrayList<>();
        for (RelationshipTriple t : store.getAllRelationships()) {
            if ("requires".equals(t.getPredicate())) {
                requiresCache.add(t);
            }
        }
        // Also cache "replaces" for contradiction detection
        replacesCache = new ArrayList<>();
        for (RelationshipTriple t : store.getAllRelationships()) {
            if ("replaces".equals(t.getPredicate())) {
                replacesCache.add(t);
            }
        }
    }
}
