package com.mkpro.maker.distillation;

import com.mkpro.CentralMemory;
import com.mkpro.facts.FactEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class LessonPromotionEngineTest {

    private LessonPromotionEngine promotionEngine;
    private FactEngine factEngine;

    @BeforeEach
    void setUp() {
        promotionEngine = new LessonPromotionEngine(2);
        factEngine = new FactEngine();
    }

    @AfterEach
    void tearDown() {
        if (factEngine != null) {
            factEngine.shutdown();
        }
    }

    private DistilledLesson createSampleLesson(String signature, String rootCause, String constraint, String pivot) {
        return DistilledLesson.builder()
                .lessonId("lesson-" + System.nanoTime())
                .signature(signature)
                .category(FailureCategory.COMPILE_ERROR)
                .rootCauseSummary(rootCause)
                .failedHypothesis("Assumed class exists on classpath")
                .negativeConstraint(constraint)
                .suggestedPivot(pivot)
                .build();
    }

    @Test
    @DisplayName("Single occurrence should record count as 1 and NOT promote")
    void testSingleOccurrenceDoesNotPromote() {
        DistilledLesson lesson = createSampleLesson(
                "missing_symbol_database",
                "Compiler error: Cannot find symbol 'executeQuery'",
                "Do NOT invoke executeQuery on Database without import",
                "Import com.mkpro.db.Database"
        );

        boolean promoted = promotionEngine.recordAndEvaluatePromotion(lesson, factEngine, null);

        assertFalse(promoted, "Single occurrence should not trigger promotion when threshold is 2");
        assertEquals(1, promotionEngine.getOccurrenceCount(lesson));
        assertTrue(promotionEngine.getPromotedSignatures().isEmpty(), "No signatures should be promoted");
        assertFalse(promotionEngine.isPromoted(lesson));

        // Verify FactEngine does not contain the relationship yet
        List<String> relationships = factEngine.queryRelationships("COMPILE_ERROR");
        boolean existsInFacts = relationships.stream()
                .anyMatch(r -> r.contains("Do NOT invoke executeQuery on Database without import"));
        assertFalse(existsInFacts, "Fact should not yet be added to FactEngine");
    }

    @Test
    @DisplayName("Second occurrence (threshold = 2) should trigger promotion to FactEngine")
    void testSecondOccurrenceTriggersPromotion() {
        DistilledLesson lesson1 = createSampleLesson(
                "missing_symbol_database",
                "Compiler error: Cannot find symbol 'executeQuery'",
                "Do NOT invoke executeQuery on Database without import",
                "Import com.mkpro.db.Database"
        );

        DistilledLesson lesson2 = createSampleLesson(
                "missing_symbol_database",
                "Compiler error: Cannot find symbol 'executeQuery'",
                "Do NOT invoke executeQuery on Database without import",
                "Import com.mkpro.db.Database"
        );

        // 1st occurrence
        boolean firstPromotion = promotionEngine.recordAndEvaluatePromotion(lesson1, factEngine, null);
        assertFalse(firstPromotion);
        assertEquals(1, promotionEngine.getOccurrenceCount(lesson1));

        // 2nd occurrence
        boolean secondPromotion = promotionEngine.recordAndEvaluatePromotion(lesson2, factEngine, null);
        assertTrue(secondPromotion, "Second occurrence should trigger promotion when threshold is 2");
        assertEquals(2, promotionEngine.getOccurrenceCount(lesson2));

        String normalizedSig = promotionEngine.computeNormalizedSignature(lesson2);
        assertTrue(promotionEngine.getPromotedSignatures().contains(normalizedSig));
        assertTrue(promotionEngine.isPromoted(lesson2));
    }

    @Test
    @DisplayName("Idempotency: Repeated occurrences beyond threshold should NOT re-promote duplicates")
    void testRepeatedOccurrencesIdempotency() {
        DistilledLesson lesson = createSampleLesson(
                "missing_symbol_database",
                "Compiler error: Cannot find symbol 'executeQuery'",
                "Do NOT invoke executeQuery on Database without import",
                "Import com.mkpro.db.Database"
        );

        // 1st occurrence -> false
        assertFalse(promotionEngine.recordAndEvaluatePromotion(lesson, factEngine, null));
        // 2nd occurrence -> true (promoted)
        assertTrue(promotionEngine.recordAndEvaluatePromotion(lesson, factEngine, null));
        // 3rd occurrence -> false (already promoted)
        assertFalse(promotionEngine.recordAndEvaluatePromotion(lesson, factEngine, null));
        // 4th occurrence -> false (already promoted)
        assertFalse(promotionEngine.recordAndEvaluatePromotion(lesson, factEngine, null));

        assertEquals(4, promotionEngine.getOccurrenceCount(lesson));
        assertEquals(1, promotionEngine.getPromotedSignatures().size());
    }

    @Test
    @DisplayName("FactEngine query retrieval should successfully verify promoted negative constraints")
    void testQueryRetrievalViaFactEngine() {
        DistilledLesson lesson = createSampleLesson(
                "syntax_missing_brace",
                "Compiler syntax error: missing closing brace",
                "Do NOT leave unclosed curly braces in method bodies",
                "Inspect brace balance with syntax parser"
        );

        promotionEngine.recordAndEvaluatePromotion(lesson, factEngine, null);
        promotionEngine.recordAndEvaluatePromotion(lesson, factEngine, null);

        // Query FactEngine relationships
        List<String> rels = factEngine.queryRelationships("COMPILE_ERROR");
        assertNotNull(rels);
        assertFalse(rels.isEmpty(), "FactEngine should return relationships for COMPILE_ERROR");

        boolean found = rels.stream().anyMatch(r ->
                r.contains("Do NOT leave unclosed curly braces") &&
                r.contains("Inspect brace balance with syntax parser"));
        assertTrue(found, "FactEngine query should contain the promoted negative constraint and suggested pivot");

        // Verify relationship direct check
        Map<String, Object> check = factEngine.checkRelationship(
                "COMPILE_ERROR",
                "Do NOT leave unclosed curly braces in method bodies",
                "Inspect brace balance with syntax parser"
        );
        assertTrue((Boolean) check.get("verified"));
    }

    @Test
    @DisplayName("CentralMemory persistence should store distilled prompt envelope on promotion")
    void testCentralMemoryPersistence() {
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "mkpro_test_" + System.nanoTime());
        Path sharedDb = tempDir.resolve("shared.db");
        Path localDb = tempDir.resolve("local.db");

        CentralMemory memory = new CentralMemory(sharedDb, localDb);
        try {
            DistilledLesson lesson = DistilledLesson.builder()
                    .lessonId("lesson-sec-1")
                    .category(FailureCategory.SECURITY_POLICY_VIOLATION)
                    .rootCauseSummary("Security policy violation: CommandPolicy rejected sudo")
                    .negativeConstraint("Do NOT invoke sudo in sandbox")
                    .suggestedPivot("Use non-root user commands")
                    .build();

            // Record twice to promote
            promotionEngine.recordAndEvaluatePromotion(lesson, factEngine, memory);
            boolean promoted = promotionEngine.recordAndEvaluatePromotion(lesson, factEngine, memory);

            assertTrue(promoted);
            String sig = promotionEngine.computeNormalizedSignature(lesson);
            String savedEnvelope = memory.getMemory("facts:negative_constraint:" + sig);
            assertNotNull(savedEnvelope);
            assertTrue(savedEnvelope.contains("<distilled_lesson"));
            assertTrue(savedEnvelope.contains("SECURITY_POLICY_VIOLATION"));
            assertTrue(savedEnvelope.contains("Do NOT invoke sudo in sandbox"));
        } finally {
            memory.close();
            deleteRecursively(tempDir.toFile());
        }
    }

    @Test
    @DisplayName("Custom promotion threshold should be respected")
    void testCustomPromotionThreshold() {
        promotionEngine.setPromotionThreshold(3);
        assertEquals(3, promotionEngine.getPromotionThreshold());

        DistilledLesson lesson = createSampleLesson(
                "sig-threshold-3",
                "Some recurring error",
                "Do NOT do X",
                "Do Y instead"
        );

        assertFalse(promotionEngine.recordAndEvaluatePromotion(lesson, factEngine, null)); // count 1
        assertFalse(promotionEngine.recordAndEvaluatePromotion(lesson, factEngine, null)); // count 2
        assertTrue(promotionEngine.recordAndEvaluatePromotion(lesson, factEngine, null));  // count 3 -> promoted
        assertFalse(promotionEngine.recordAndEvaluatePromotion(lesson, factEngine, null)); // count 4 -> already promoted

        assertEquals(4, promotionEngine.getOccurrenceCount(lesson));
    }

    @Test
    @DisplayName("Multiple distinct lessons should track occurrence counts independently")
    void testMultipleDistinctLessons() {
        DistilledLesson lessonA = DistilledLesson.builder()
                .category(FailureCategory.COMPILE_ERROR)
                .rootCauseSummary("Type mismatch error")
                .negativeConstraint("Do NOT cast Foo to Bar")
                .suggestedPivot("Use FooAdapter")
                .build();

        DistilledLesson lessonB = DistilledLesson.builder()
                .category(FailureCategory.TEST_FAILURE)
                .rootCauseSummary("Assertion failed: expected 10 but was 0")
                .negativeConstraint("Do NOT return empty list when results exist")
                .suggestedPivot("Populate list before return")
                .build();

        // 1 of A, 1 of B
        assertFalse(promotionEngine.recordAndEvaluatePromotion(lessonA, factEngine));
        assertFalse(promotionEngine.recordAndEvaluatePromotion(lessonB, factEngine));

        // 2 of A -> Promoted!
        assertTrue(promotionEngine.recordAndEvaluatePromotion(lessonA, factEngine));
        assertTrue(promotionEngine.isPromoted(lessonA));
        assertFalse(promotionEngine.isPromoted(lessonB));

        // 2 of B -> Promoted!
        assertTrue(promotionEngine.recordAndEvaluatePromotion(lessonB, factEngine));
        assertTrue(promotionEngine.isPromoted(lessonB));

        Set<String> promoted = promotionEngine.getPromotedSignatures();
        assertEquals(2, promoted.size());
    }

    @Test
    @DisplayName("Clear should reset counters, lesson cache, and promoted signatures")
    void testClear() {
        DistilledLesson lesson = createSampleLesson("sig-clear", "Error", "Constraint", "Pivot");
        promotionEngine.recordAndEvaluatePromotion(lesson, factEngine);
        promotionEngine.recordAndEvaluatePromotion(lesson, factEngine);

        assertEquals(1, promotionEngine.getPromotedSignatures().size());
        assertEquals(2, promotionEngine.getOccurrenceCount(lesson));

        promotionEngine.clear();

        assertEquals(0, promotionEngine.getPromotedSignatures().size());
        assertEquals(0, promotionEngine.getOccurrenceCount(lesson));
        assertEquals(0, promotionEngine.getLessonCache().size());
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
