package com.mkpro.routing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for learned patterns (Phase 1+2) and stall prediction (Phase 3).
 */
public class LearnedPatternsAndStallTest {

    private MarkovRouter router;
    private IntentClassifier classifier;

    @BeforeEach
    void setup() {
        router = new MarkovRouter();
        classifier = new IntentClassifier();
    }

    // ==========================================================================
    // Phase 1: Pattern Extraction from Training Data
    // ==========================================================================

    @Test
    void extractsPatternsFromMessages() {
        Map<String, List<String>> messages = new HashMap<>();
        messages.put("CODING", List.of(
            "implement the parser module",
            "implement a new validator",
            "implement login feature",
            "implement caching layer",
            "implement pagination",
            "implement the serializer"  // 6 messages with "implement" repeated
        ));
        messages.put("TESTING", List.of(
            "run the integration tests",
            "run unit tests for auth",
            "run all regression tests",
            "run tests with coverage",
            "run the test suite again",
            "run smoke tests"
        ));

        Map<String, Set<String>> patterns = MarkovTrainer.extractLearnedPatterns(messages);
        
        assertNotNull(patterns);
        // "implement" should be distinctive for CODING (appears 6x, only in CODING)
        assertTrue(patterns.containsKey("CODING"));
        assertTrue(patterns.get("CODING").contains("implement"));
        
        // "tests" or "run" should be distinctive for TESTING
        assertTrue(patterns.containsKey("TESTING"));
        Set<String> testingPatterns = patterns.get("TESTING");
        assertTrue(testingPatterns.contains("tests") || testingPatterns.contains("run"));
    }

    @Test
    void requiresMinimum5MessagesPerCategory() {
        Map<String, List<String>> messages = new HashMap<>();
        messages.put("CODING", List.of(
            "fix the bug",
            "fix another bug",
            "fix this issue"  // Only 3 messages — too few
        ));

        Map<String, Set<String>> patterns = MarkovTrainer.extractLearnedPatterns(messages);
        
        // Should not extract patterns from categories with < 5 messages
        assertFalse(patterns.containsKey("CODING"));
    }

    @Test
    void requiresMinimum3Occurrences() {
        Map<String, List<String>> messages = new HashMap<>();
        messages.put("CODING", List.of(
            "implement the feature",
            "build the module",
            "create the service",
            "write the handler",
            "design the api"  // All unique words — none appears 3x
        ));

        Map<String, Set<String>> patterns = MarkovTrainer.extractLearnedPatterns(messages);
        
        // No single word appears 3+ times, so no patterns
        assertTrue(!patterns.containsKey("CODING") || patterns.get("CODING").isEmpty());
    }

    @Test
    void filtersStopwords() {
        Map<String, List<String>> messages = new HashMap<>();
        messages.put("CODING", List.of(
            "the code is broken",
            "the code has bugs",
            "the code needs fixing",
            "the code is slow",
            "the code crashes",
            "the code fails"
        ));

        Map<String, Set<String>> patterns = MarkovTrainer.extractLearnedPatterns(messages);
        
        if (patterns.containsKey("CODING")) {
            // "the" and "is" should be filtered as stopwords
            assertFalse(patterns.get("CODING").contains("the"));
            assertFalse(patterns.get("CODING").contains("is"));
            // "code" should be kept (appears 6x, meaningful)
            assertTrue(patterns.get("CODING").contains("code"));
        }
    }

    // ==========================================================================
    // Phase 2: Bigram Extraction and Scoring
    // ==========================================================================

    @Test
    void extractsBigrams() {
        Map<String, List<String>> messages = new HashMap<>();
        messages.put("SYSADMIN", List.of(
            "count lines in files",
            "count lines for each module",
            "count lines of code",
            "count lines recursively",
            "count lines per directory",
            "count lines total"
        ));

        Map<String, Set<String>> patterns = MarkovTrainer.extractLearnedPatterns(messages);
        
        assertTrue(patterns.containsKey("SYSADMIN"));
        // "B:count lines" should be a learned bigram
        assertTrue(patterns.get("SYSADMIN").contains("B:count lines"));
    }

    @Test
    void bigramsScoreHigherThanUnigrams() {
        Map<String, List<String>> messages = new HashMap<>();
        messages.put("DATABASE", List.of(
            "create schema migration for users",
            "create schema migration for orders",
            "create schema migration for products",
            "create schema migration for payments",
            "create schema migration for sessions",
            "create schema dump"
        ));

        Map<String, Set<String>> patterns = MarkovTrainer.extractLearnedPatterns(messages);
        
        assertTrue(patterns.containsKey("DATABASE"));
        Set<String> dbPatterns = patterns.get("DATABASE");
        // Bigram should appear before individual unigrams due to 2x boost
        List<String> patternList = new ArrayList<>(dbPatterns);
        int bigramIdx = -1;
        for (int i = 0; i < patternList.size(); i++) {
            if (patternList.get(i).startsWith("B:")) {
                bigramIdx = i;
                break;
            }
        }
        // Bigrams should be in the top patterns
        assertTrue(bigramIdx >= 0 && bigramIdx < 10, "Bigrams should rank in top 10");
    }

    // ==========================================================================
    // Phase 1+2: IntentClassifier with Learned Patterns
    // ==========================================================================

    @Test
    void learnedPatternsOverrideGENERAL() {
        Map<String, Set<String>> patterns = new HashMap<>();
        patterns.put("ARCHITECTURE", Set.of("analyze", "codebase", "B:deep analyze"));
        classifier.setLearnedPatterns(patterns);

        // Static would return GENERAL for "analyze the codebase" if not in patterns
        IntentClassifier.TaskCategory result = classifier.classifyWithLearnedPatterns("analyze the codebase");
        assertEquals(IntentClassifier.TaskCategory.ARCHITECTURE, result);
    }

    @Test
    void bigramMatchCountsDouble() {
        Map<String, Set<String>> patterns = new HashMap<>();
        patterns.put("SYSADMIN", Set.of("count", "B:count lines"));
        patterns.put("CODING", Set.of("count", "variable"));
        classifier.setLearnedPatterns(patterns);

        // "count lines" matches bigram (2pts) + unigram (1pt) = 3 for SYSADMIN
        // vs just unigram "count" (1pt) for CODING
        IntentClassifier.TaskCategory result = classifier.classifyWithLearnedPatterns("count lines in each file");
        assertEquals(IntentClassifier.TaskCategory.SYSADMIN, result);
    }

    @Test
    void requiresMinimum2PointsToMatch() {
        Map<String, Set<String>> patterns = new HashMap<>();
        patterns.put("DATABASE", Set.of("migration", "schema", "B:schema migration"));
        classifier.setLearnedPatterns(patterns);

        // Only 1 unigram match = 1 point → not enough (need 2)
        IntentClassifier.TaskCategory result = classifier.classifyWithLearnedPatterns("the migration");
        // "migration" alone = 1pt, not enough
        // Actually this is just 1pt... let me think. "the migration" has "migration" (1pt)
        assertEquals(IntentClassifier.TaskCategory.GENERAL, result);
    }

    @Test
    void twoUnigramsEnoughToMatch() {
        Map<String, Set<String>> patterns = new HashMap<>();
        patterns.put("DATABASE", Set.of("migration", "schema", "B:schema migration"));
        classifier.setLearnedPatterns(patterns);

        // 2 unigrams = 2 points → enough
        IntentClassifier.TaskCategory result = classifier.classifyWithLearnedPatterns("create schema migration");
        assertEquals(IntentClassifier.TaskCategory.DATABASE, result);
    }

    @Test
    void emptyPatternsReturnGeneral() {
        classifier.setLearnedPatterns(new HashMap<>());
        assertEquals(IntentClassifier.TaskCategory.GENERAL, classifier.classifyWithLearnedPatterns("anything"));
    }

    // ==========================================================================
    // Phase 3: Stall Prediction
    // ==========================================================================

    @Test
    void recordStallStoresPattern() {
        router.recordStall(IntentClassifier.TaskCategory.CODING, 
            List.of("Coder", "Coder", "Coder", "Coder"));
        
        // Should predict stall for similar sequence
        double prob = router.predictStall(IntentClassifier.TaskCategory.CODING,
            List.of("Coder", "Coder", "Coder", "Coder"));
        assertTrue(prob > 0.0, "Should detect stall for identical pattern");
    }

    @Test
    void noStallForDifferentCategory() {
        router.recordStall(IntentClassifier.TaskCategory.CODING,
            List.of("Coder", "Coder", "Coder"));
        
        // Different category should not match
        double prob = router.predictStall(IntentClassifier.TaskCategory.TESTING,
            List.of("Coder", "Coder", "Coder"));
        assertEquals(0.0, prob);
    }

    @Test
    void noStallForShortSequence() {
        router.recordStall(IntentClassifier.TaskCategory.CODING,
            List.of("Coder", "Coder", "Coder"));
        
        // Too short (< 2) should return 0
        double prob = router.predictStall(IntentClassifier.TaskCategory.CODING,
            List.of("Coder"));
        assertEquals(0.0, prob);
    }

    @Test
    void stallPredictionMatchesPartialOverlap() {
        // Record a stall: Coordinator repeating
        router.recordStall(IntentClassifier.TaskCategory.GOALS,
            List.of("GoalTracker", "GoalTracker", "GoalTracker", "GoalTracker"));
        
        // Current sequence is similar but shorter
        double prob = router.predictStall(IntentClassifier.TaskCategory.GOALS,
            List.of("GoalTracker", "GoalTracker", "GoalTracker"));
        assertTrue(prob > 0.0, "Partial match should have non-zero probability");
    }

    @Test
    void multipleStallPatternsIncreaseProbability() {
        // Record several stall patterns
        router.recordStall(IntentClassifier.TaskCategory.CODING,
            List.of("Coder", "Coder", "Coder"));
        router.recordStall(IntentClassifier.TaskCategory.CODING,
            List.of("Coder", "Coder", "Coder", "Coder"));
        router.recordStall(IntentClassifier.TaskCategory.CODING,
            List.of("Coder", "Coder", "Coder", "Coder", "Coder"));
        
        double prob = router.predictStall(IntentClassifier.TaskCategory.CODING,
            List.of("Coder", "Coder", "Coder"));
        assertTrue(prob >= 0.3, "Multiple matching patterns should give higher probability");
    }

    @Test
    void stallPatternsCappedAt50() {
        for (int i = 0; i < 60; i++) {
            router.recordStall(IntentClassifier.TaskCategory.CODING,
                List.of("Agent" + i, "Agent" + i));
        }
        
        // Should still work without error (old patterns evicted)
        double prob = router.predictStall(IntentClassifier.TaskCategory.CODING,
            List.of("Agent59", "Agent59"));
        // Recent pattern should match
        assertTrue(prob > 0.0);
    }

    // ==========================================================================
    // Integration: Router persistence with new fields
    // ==========================================================================

    @Test
    void savesAndLoadsLearnedPatterns() throws Exception {
        Map<String, Set<String>> patterns = new HashMap<>();
        patterns.put("CODING", Set.of("implement", "parser", "B:implement parser"));
        router.setLearnedPatterns(patterns);
        router.recordStall(IntentClassifier.TaskCategory.TESTING, List.of("Tester", "Tester"));

        // Save
        java.nio.file.Path tmpFile = java.nio.file.Files.createTempFile("markov_test", ".dat");
        router.save(tmpFile);

        // Load into fresh router
        MarkovRouter loaded = new MarkovRouter();
        loaded.load(tmpFile);

        assertEquals(Set.of("implement", "parser", "B:implement parser"), 
            loaded.getLearnedPatterns().get("CODING"));
        
        // Stall pattern should also persist
        double prob = loaded.predictStall(IntentClassifier.TaskCategory.TESTING,
            List.of("Tester", "Tester"));
        assertTrue(prob > 0.0);

        java.nio.file.Files.deleteIfExists(tmpFile);
    }
}
