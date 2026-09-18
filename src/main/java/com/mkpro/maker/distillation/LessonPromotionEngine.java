package com.mkpro.maker.distillation;

import com.mkpro.CentralMemory;
import com.mkpro.facts.Fact;
import com.mkpro.facts.FactEngine;
import com.mkpro.facts.RelationshipTriple;

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
    private FactEngine factEngine;
    private CentralMemory memory;

    public LessonPromotionEngine() {
        this(2);
    }

    public LessonPromotionEngine(int promotionThreshold) {
        this.promotionThreshold = Math.max(1, promotionThreshold);
    }

    public LessonPromotionEngine(FactEngine factEngine) {
        this(2);
        this.factEngine = factEngine;
    }

    public LessonPromotionEngine(FactEngine factEngine, CentralMemory memory) {
        this.promotionThreshold = 2;
        this.factEngine = factEngine;
        this.memory = memory;
    }

    public LessonPromotionEngine(int promotionThreshold, FactEngine factEngine) {
        this.promotionThreshold = promotionThreshold;
        this.factEngine = factEngine;
    }

    public LessonPromotionEngine(int promotionThreshold, FactEngine factEngine, CentralMemory memory) {
        this.promotionThreshold = promotionThreshold;
        this.factEngine = factEngine;
        this.memory = memory;
    }

    public void setFactEngine(FactEngine factEngine) {
        this.factEngine = factEngine;
    }

    public FactEngine getFactEngine() {
        return this.factEngine;
    }

    public void setMemory(CentralMemory memory) {
        this.memory = memory;
    }

    public CentralMemory getMemory() {
        return this.memory;
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
     * Promotes a lesson directly, registering relationship triple and adding fact.
     *
     * @param lesson The distilled lesson to promote
     */
    public synchronized void promoteLesson(DistilledLesson lesson) {
        if (lesson == null || this.factEngine == null) return;
        String signature = computeNormalizedSignature(lesson);
        lessonCache.put(signature, lesson);
        promotedSignatures.add(signature);

        String category = lesson.getCategory() != null ? lesson.getCategory().name() : "GENERAL";
        String constraint = lesson.getNegativeConstraint() != null ? lesson.getNegativeConstraint() : "";
        String pivot = lesson.getSuggestedPivot() != null ? lesson.getSuggestedPivot() : "";
        double conf = lesson.getConfidence();

        this.factEngine.addFact(new Fact(category, constraint, constraint + " -> " + pivot, conf));
        this.factEngine.addFact(new Fact(category, pivot, constraint + " -> " + pivot, conf));
        this.factEngine.addFact(new Fact("NEGATIVE_CONSTRAINT", category, constraint + " -> " + pivot, conf));
        this.factEngine.addFact(new Fact(category, category, constraint + " -> " + pivot, conf));

        if (this.factEngine.getRelationshipGraph() != null) {
            this.factEngine.getRelationshipGraph().addTriple(
                new RelationshipTriple(category, constraint, pivot, "lesson_promotion", conf)
            );
        }

        if (this.memory != null) {
            try {
                String memKey = "facts:negative_constraint:" + signature;
                this.memory.saveMemory(memKey, lesson.toPromptEnvelope());
            } catch (Exception e) {
                System.err.println("[LessonPromotionEngine] Failed to persist lesson in CentralMemory: " + e.getMessage());
            }
        }
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
            applyPromotion(lesson, signature, factEngine, memory);
            return true;
        }

        return false;
    }

    /**
     * Applies the promotion of a distilled lesson into FactEngine and CentralMemory.
     */
    private void applyPromotion(DistilledLesson lesson, String signature, FactEngine factEngine, CentralMemory memory) {
        if (lesson == null) return;
        String category = lesson.getCategory() != null ? lesson.getCategory().name() : "GENERAL";
        String constraint = lesson.getNegativeConstraint() != null ? lesson.getNegativeConstraint() : "";
        String pivot = lesson.getSuggestedPivot() != null ? lesson.getSuggestedPivot() : "";
        double conf = lesson.getConfidence();

        FactEngine targetEngine = (factEngine != null) ? factEngine : this.factEngine;
        if (targetEngine != null) {
            // Register comprehensive facts covering both constraint and pivot
            targetEngine.addFact(new Fact(category, constraint, constraint + " -> " + pivot, conf));
            targetEngine.addFact(new Fact(category, pivot, constraint + " -> " + pivot, conf));
            targetEngine.addFact(new Fact("NEGATIVE_CONSTRAINT", category, constraint + " -> " + pivot, conf));
            targetEngine.addFact(new Fact(category, category, constraint + " -> " + pivot, conf));

            if (targetEngine.getRelationshipGraph() != null) {
                targetEngine.getRelationshipGraph().addTriple(
                    new RelationshipTriple(category, constraint, pivot, "lesson_promotion", conf)
                );
            }
        }

        CentralMemory targetMemory = (memory != null) ? memory : this.memory;
        if (targetMemory != null) {
            try {
                String memKey = "facts:negative_constraint:" + signature;
                targetMemory.saveMemory(memKey, lesson.toPromptEnvelope());
            } catch (Exception e) {
                System.err.println("[LessonPromotionEngine] Failed to persist lesson in CentralMemory: " + e.getMessage());
            }
        }
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
     * Checks if a specific lesson signature has already been promoted.
     */
    public boolean isPromoted(String signature) {
        if (signature == null) {
            return false;
        }
        return promotedSignatures.contains(signature);
    }

    /**
     * Checks if a distilled lesson has already been promoted.
     */
    public boolean isPromoted(DistilledLesson lesson) {
        return isPromoted(computeNormalizedSignature(lesson));
    }

    /**
     * Sets the promotion occurrence threshold (minimum occurrences before promotion).
     */
    public void setPromotionThreshold(int threshold) {
        this.promotionThreshold = Math.max(1, threshold);
    }

    public int getPromotionThreshold() {
        return promotionThreshold;
    }

    /**
     * Returns the lesson cache mapping signatures to distilled lessons.
     */
    public Map<String, DistilledLesson> getLessonCache() {
        return this.lessonCache != null ? this.lessonCache : Collections.emptyMap();
    }

    /**
     * Resets counters and promoted cache.
     */
    public synchronized void clear() {
        occurrenceCounter.clear();
        lessonCache.clear();
        promotedSignatures.clear();
    }
}