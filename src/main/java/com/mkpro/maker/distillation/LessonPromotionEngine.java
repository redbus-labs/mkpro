package com.mkpro.maker.distillation;

import com.mkpro.CentralMemory;
import com.mkpro.facts.Fact;
import com.mkpro.facts.FactEngine;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-Goal Distilled Lesson Promotion Engine.
 *
 * Tracks recurring distilled lessons across trajectory runs, counts occurrences,
 * and automatically promotes high-frequency failure lessons into verified persistent
 * Facts in {@link FactEngine} and {@link CentralMemory} as negative constraints.
 */
public class LessonPromotionEngine {

    private final Map<String, Integer> occurrenceCounter = new ConcurrentHashMap<>();
    private final Map<String, DistilledLesson> lessonCache = new ConcurrentHashMap<>();
    private final Set<String> promotedSignatures = ConcurrentHashMap.newKeySet();
    private int promotionThreshold = 2;

    public LessonPromotionEngine() {
        this(2);
    }

    public LessonPromotionEngine(int promotionThreshold) {
        this.promotionThreshold = Math.max(1, promotionThreshold);
    }

    /**
     * Computes a normalized signature for a distilled lesson in format:
     * category:signature (or sanitized root cause).
     *
     * @param lesson The distilled lesson
     * @return Normalized signature string
     */
    public String computeNormalizedSignature(DistilledLesson lesson) {
        if (lesson == null) {
            return "UNKNOWN:unknown";
        }
        String categoryStr = (lesson.getCategory() != null) ? lesson.getCategory().name() : "UNKNOWN";
        String sig = lesson.getSignature();
        if (sig == null || sig.isBlank()) {
            String rc = lesson.getRootCauseSummary();
            sig = (rc != null && !rc.isBlank()) ? rc.replaceAll("\\s+", " ").trim() : "unknown";
        } else {
            sig = sig.replaceAll("\\s+", " ").trim();
        }
        return categoryStr + ":" + sig;
    }

    /**
     * Records a distilled lesson occurrence and evaluates whether it should be promoted to
     * persistent knowledge in FactEngine and CentralMemory.
     *
     * @param lesson     The distilled lesson to record and evaluate
     * @param factEngine The FactEngine to inject persistent facts into (optional)
     * @param memory     The CentralMemory store (optional)
     * @return true if the lesson was promoted during this call, false otherwise
     */
    public synchronized boolean recordAndEvaluatePromotion(DistilledLesson lesson, FactEngine factEngine, CentralMemory memory) {
        if (lesson == null) {
            return false;
        }

        String signature = computeNormalizedSignature(lesson);
        lessonCache.put(signature, lesson);
        int count = occurrenceCounter.merge(signature, 1, Integer::sum);

        if (count >= promotionThreshold && !promotedSignatures.contains(signature)) {
            promotedSignatures.add(signature);

            // Construct persistent Fact
            String subject = (lesson.getCategory() != null) ? lesson.getCategory().name() : "UNKNOWN";
            String negativeConstraints = lesson.getNegativeConstraint();
            if (negativeConstraints == null || negativeConstraints.isBlank()) {
                negativeConstraints = (lesson.getContext() != null && !lesson.getContext().isBlank())
                        ? "avoids: " + lesson.getContext()
                        : "avoids failure repetition";
            }

            String suggestedPivot = lesson.getSuggestedPivot();
            if (suggestedPivot == null || suggestedPivot.isBlank()) {
                suggestedPivot = "pivot to alternative strategy";
            }

            String predicate = negativeConstraints;
            String object = suggestedPivot;

            Fact fact = new Fact("NEGATIVE_CONSTRAINT", subject, predicate, object);

            if (factEngine != null) {
                factEngine.addFact(fact);
            }

            if (memory != null) {
                try {
                    String memKey = "facts:negative_constraint:" + signature;
                    memory.saveMemory(memKey, lesson.toPromptEnvelope());
                } catch (Exception e) {
                    System.err.println("[LessonPromotionEngine] Failed to persist lesson in CentralMemory: " + e.getMessage());
                }
            }

            return true;
        }

        return false;
    }

    /**
     * Overload for recording and evaluating promotion with FactEngine only.
     */
    public synchronized boolean recordAndEvaluatePromotion(DistilledLesson lesson, FactEngine factEngine) {
        return recordAndEvaluatePromotion(lesson, factEngine, null);
    }

    /**
     * Overload for recording and evaluating promotion without external stores.
     */
    public synchronized boolean recordAndEvaluatePromotion(DistilledLesson lesson) {
        return recordAndEvaluatePromotion(lesson, null, null);
    }

    /**
     * Gets the occurrence count for a normalized lesson signature.
     */
    public int getOccurrenceCount(String signature) {
        if (signature == null) {
            return 0;
        }
        return occurrenceCounter.getOrDefault(signature, 0);
    }

    /**
     * Gets the occurrence count for a distilled lesson.
     */
    public int getOccurrenceCount(DistilledLesson lesson) {
        return getOccurrenceCount(computeNormalizedSignature(lesson));
    }

    /**
     * Returns an unmodifiable set of all promoted lesson signatures.
     */
    public Set<String> getPromotedSignatures() {
        return Collections.unmodifiableSet(promotedSignatures);
    }

    /**
     * Checks if a specific signature has been promoted.
     */
    public boolean isPromoted(String signature) {
        if (signature == null) {
            return false;
        }
        return promotedSignatures.contains(signature);
    }

    /**
     * Checks if a specific lesson has been promoted.
     */
    public boolean isPromoted(DistilledLesson lesson) {
        return isPromoted(computeNormalizedSignature(lesson));
    }

    /**
     * Gets the promotion threshold.
     */
    public int getPromotionThreshold() {
        return promotionThreshold;
    }

    /**
     * Sets the promotion threshold (minimum 1).
     */
    public void setPromotionThreshold(int threshold) {
        this.promotionThreshold = Math.max(1, threshold);
    }

    /**
     * Retrieves the cached DistilledLesson for a signature, or null if not recorded.
     */
    public DistilledLesson getLesson(String signature) {
        if (signature == null) {
            return null;
        }
        return lessonCache.get(signature);
    }

    /**
     * Returns an unmodifiable view of the occurrence counter map.
     */
    public Map<String, Integer> getOccurrenceCounter() {
        return Collections.unmodifiableMap(occurrenceCounter);
    }

    /**
     * Returns an unmodifiable view of the lesson cache.
     */
    public Map<String, DistilledLesson> getLessonCache() {
        return Collections.unmodifiableMap(lessonCache);
    }

    /**
     * Resets counters, caches, and promoted signatures.
     */
    public void clear() {
        occurrenceCounter.clear();
        lessonCache.clear();
        promotedSignatures.clear();
    }
}
