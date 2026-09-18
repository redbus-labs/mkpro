package com.mkpro.facts;

import java.util.*;

/**
 * Zero-latency keyword → fact domain matching.
 * Scans input text for keywords that match known facts.
 * No LLM call — pure string matching.
 */
public class FactClassifier {

    private final FactStore store;

    public FactClassifier(FactStore store) {
        this.store = store;
    }

    /**
     * Find math facts relevant to the given text, ranked by keyword overlap.
     * Returns top 3 most relevant facts (more matching keywords = higher rank).
     */
    public List<MathFact> findRelevantMathFacts(String text) {
        if (text == null || text.isBlank()) return Collections.emptyList();

        String lower = text.toLowerCase();
        List<ScoredFact> scored = new ArrayList<>();

        for (MathFact fact : store.getAllMathFacts()) {
            int matchCount = 0;
            for (String keyword : fact.getKeywords()) {
                if (lower.contains(keyword)) {
                    matchCount++;
                }
            }
            // Require at least 2 keyword matches to avoid false positives
            // (e.g., "force" alone shouldn't trigger F=ma; need "force" + "mass" or "acceleration")
            if (matchCount >= 2) {
                double score = (double) matchCount / fact.getKeywords().size();
                score += 0.3; // Multi-match bonus
                scored.add(new ScoredFact(fact, score));
            } else if (matchCount == 1 && fact.getKeywords().size() == 1) {
                // Single-keyword facts are very specific (e.g., "pythagor") — allow them
                scored.add(new ScoredFact(fact, 0.5));
            }
        }

        if (scored.isEmpty()) return Collections.emptyList();

        // Sort by score descending, take top 3
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        int limit = Math.min(3, scored.size());
        List<MathFact> results = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            results.add(scored.get(i).fact);
        }
        return results;
    }

    private static class ScoredFact {
        final MathFact fact;
        final double score;
        ScoredFact(MathFact fact, double score) { this.fact = fact; this.score = score; }
    }

    /**
     * Find relationship triples relevant to the given text, ranked by specificity.
     * Matches subject or object mentions. Caps at top 5.
     */
    public List<RelationshipTriple> findRelevantRelationships(String text) {
        if (text == null || text.isBlank()) return Collections.emptyList();

        String lower = text.toLowerCase();
        List<RelationshipTriple> results = new ArrayList<>();

        for (RelationshipTriple triple : store.getAllRelationships()) {
            boolean subjectMatch = lower.contains(triple.getSubject().toLowerCase());
            boolean objectMatch = lower.contains(triple.getObject().toLowerCase());
            // Require subject match (more specific than just object match)
            if (subjectMatch) {
                results.add(triple);
            }
        }

        // Cap at 5 to avoid noise
        if (results.size() > 5) {
            return results.subList(0, 5);
        }
        return results;
    }

    /**
     * Check if the text likely involves mathematical computation.
     * Used to decide whether to run post-turn validation.
     */
    public boolean likelyInvolvesMath(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();

        // Check for numerical patterns: "= 50", "equals 123.4", etc.
        if (lower.matches(".*\\d+\\.?\\d*\\s*[×*x/+\\-]\\s*\\d+.*")) return true;

        // Check for math keywords
        String[] mathSignals = {"calculate", "compute", "formula", "equation",
            "area", "volume", "distance", "radius", "velocity", "force",
            "energy", "power", "resistance", "throughput", "latency"};
        for (String signal : mathSignals) {
            if (lower.contains(signal)) return true;
        }
        return false;
    }
}
