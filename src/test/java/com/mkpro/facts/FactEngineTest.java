package com.mkpro.facts;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the FactEngine system: FactStore, FactClassifier, RelationshipGraph,
 * RelationshipValidator, GroovyFactEvaluator, and FactEngine orchestration.
 */
public class FactEngineTest {

    private FactEngine engine;

    @BeforeEach
    void setUp() {
        engine = new FactEngine();
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    // ═══ FactStore loading ═══

    @Test
    void storeLoadsMathFacts() {
        assertTrue(engine.getStore().mathFactCount() > 30);
    }

    @Test
    void storeLoadsRelationships() {
        assertTrue(engine.getStore().relationshipCount() > 50);
    }

    @Test
    void storeFindsFactByKey() {
        MathFact fact = engine.getStore().getMathFact("geometry.circle_area");
        assertNotNull(fact);
        assertEquals("A = π × r²", fact.getFormula());
        assertNotNull(fact.getScript());
    }

    @Test
    void storeFindsFactByKeyword() {
        List<MathFact> facts = engine.getStore().findByKeywords(List.of("circle"));
        assertFalse(facts.isEmpty());
        assertTrue(facts.stream().anyMatch(f -> f.getKey().contains("circle")));
    }

    // ═══ FactClassifier ═══

    @Test
    void classifierFindsMathFactsFromText() {
        List<MathFact> facts = engine.getClassifier().findRelevantMathFacts(
            "calculate the area of a circle with radius 5");
        assertFalse(facts.isEmpty());
        assertTrue(facts.stream().anyMatch(f -> f.getFormula().contains("π")));
    }

    @Test
    void classifierFindsRelationshipsFromText() {
        List<RelationshipTriple> rels = engine.getClassifier().findRelevantRelationships(
            "does HPA require metrics-server for autoscaling?");
        assertFalse(rels.isEmpty());
    }

    @Test
    void classifierReturnsEmptyForUnrelatedText() {
        List<MathFact> facts = engine.getClassifier().findRelevantMathFacts(
            "please refactor the variable naming");
        assertTrue(facts.isEmpty());
    }

    @Test
    void classifierDetectsMathInText() {
        assertTrue(engine.getClassifier().likelyInvolvesMath("calculate the area"));
        assertTrue(engine.getClassifier().likelyInvolvesMath("what is the volume?"));
        assertFalse(engine.getClassifier().likelyInvolvesMath("rename the class"));
    }

    // ═══ GroovyFactEvaluator (Math verification) ═══

    @Test
    void verifyCircleArea() {
        Map<String, Object> result = engine.verifyMath("geometry.circle_area", Map.of("r", 5.0));
        assertFalse(result.containsKey("error"), "Error: " + result.get("error"));
        double value = ((Number) result.get("result")).doubleValue();
        assertEquals(78.54, value, 0.01);
    }

    @Test
    void verifySphereVolume() {
        Map<String, Object> result = engine.verifyMath("geometry.sphere_volume", Map.of("r", 3.0));
        assertFalse(result.containsKey("error"));
        double value = ((Number) result.get("result")).doubleValue();
        assertEquals(113.1, value, 0.1);
    }

    @Test
    void verifyPythagorean() {
        Map<String, Object> result = engine.verifyMath("geometry.pythagorean", Map.of("a", 3.0, "b", 4.0));
        assertFalse(result.containsKey("error"));
        double value = ((Number) result.get("result")).doubleValue();
        assertEquals(5.0, value, 0.001);
    }

    @Test
    void verifyNewtonSecondLaw() {
        Map<String, Object> result = engine.verifyMath("mechanics.newton_second", Map.of("m", 10.0, "a", 9.8));
        assertFalse(result.containsKey("error"));
        double value = ((Number) result.get("result")).doubleValue();
        assertEquals(98.0, value, 0.01);
    }

    @Test
    void verifyOhmsLaw() {
        Map<String, Object> result = engine.verifyMath("electricity.ohms_law", Map.of("I", 2.0, "R", 50.0));
        assertFalse(result.containsKey("error"));
        double value = ((Number) result.get("result")).doubleValue();
        assertEquals(100.0, value, 0.01);
    }

    @Test
    void verifyQuadraticFormula() {
        Map<String, Object> result = engine.verifyMath("algebra.quadratic_formula", Map.of("a", 1.0, "b", -5.0, "c", 6.0));
        assertFalse(result.containsKey("error"));
        // x² - 5x + 6 = 0 → roots are 3 and 2
        Object roots = result.get("result");
        assertNotNull(roots);
    }

    @Test
    void verifyByKeyword() {
        // Look up by keyword instead of exact key
        Map<String, Object> result = engine.verifyMath("throughput", Map.of("bandwidth", 1000.0, "packet_loss", 0.1));
        assertFalse(result.containsKey("error"));
        double value = ((Number) result.get("result")).doubleValue();
        assertEquals(900.0, value, 0.01);
    }

    @Test
    void verifyUnknownFactReturnsError() {
        Map<String, Object> result = engine.verifyMath("nonexistent.fact", Map.of("x", 1.0));
        assertTrue(result.containsKey("error"));
    }

    @Test
    void validateMathCorrect() {
        Map<String, Object> result = engine.validateMath("geometry.circle_area",
            Map.of("r", 5.0, "A", 78.54));
        assertFalse(result.containsKey("error"), "Error: " + result.get("error"));
        assertTrue((Boolean) result.get("correct"));
    }

    @Test
    void validateMathIncorrect() {
        Map<String, Object> result = engine.validateMath("geometry.circle_area",
            Map.of("r", 5.0, "A", 50.0));
        if (!result.containsKey("error")) {
            assertFalse((Boolean) result.get("correct"));
        }
    }

    // ═══ RelationshipGraph + Validator ═══

    @Test
    void directRelationshipCheck() {
        Map<String, Object> result = engine.checkRelationship("HPA", "requires", "metrics-server");
        assertTrue((Boolean) result.get("verified"));
        assertEquals("direct", result.get("type"));
    }

    @Test
    void transitiveRelationshipCheck() {
        Map<String, Object> result = engine.checkRelationship("HPA", "requires", "Kubernetes 1.8+");
        assertTrue((Boolean) result.get("verified"));
        assertEquals("transitive", result.get("type"));
        List<?> chain = (List<?>) result.get("chain");
        assertTrue(chain.size() >= 3);
    }

    @Test
    void nonExistentRelationship() {
        Map<String, Object> result = engine.checkRelationship("HPA", "requires", "MongoDB");
        assertFalse((Boolean) result.get("verified"));
    }

    @Test
    void queryRelationships() {
        List<String> rels = engine.queryRelationships("Spring Boot 3");
        assertFalse(rels.isEmpty(), "Expected relationships for 'Spring Boot 3'");
    }

    @Test
    void queryUnknownSubject() {
        List<String> rels = engine.queryRelationships("NonexistentThing");
        assertTrue(rels.isEmpty());
    }

    // ═══ Claim validation ═══

    @Test
    void validateClaimWithConflict() {
        List<String> issues = engine.validateResponse("Configure HPA without metrics-server for autoscaling");
        assertFalse(issues.isEmpty());
        assertTrue(issues.get(0).contains("CONFLICT"));
    }

    @Test
    void validateClaimNoConflict() {
        List<String> issues = engine.validateResponse("Configure HPA with metrics-server for autoscaling");
        assertTrue(issues.isEmpty());
    }

    // ═══ Pre-turn fact injection ═══

    @Test
    void getRelevantFactsForMath() {
        String facts = engine.getRelevantFacts("calculate the area of a circle with radius 10");
        assertNotNull(facts);
        assertTrue(facts.contains("π"));
    }

    @Test
    void getRelevantFactsForRelationships() {
        String facts = engine.getRelevantFacts("set up HPA autoscaling for our deployment");
        assertNotNull(facts);
        assertTrue(facts.contains("requires") || facts.contains("HPA"));
    }

    @Test
    void getRelevantFactsReturnsNullForUnrelated() {
        String facts = engine.getRelevantFacts("rename this variable to camelCase");
        assertNull(facts);
    }

    // ═══ Graph structure ═══

    @Test
    void graphHasNodes() {
        assertTrue(engine.getGraph().nodeCount() > 40);
    }

    @Test
    void graphHasEdges() {
        assertTrue(engine.getGraph().edgeCount() > 50);
    }

    @Test
    void contradictionDetection() {
        // TLS 1.3 replaces TLS 1.2 is in the graph
        // Adding TLS 1.2 replaces TLS 1.3 should be a contradiction
        String contradiction = engine.getGraph().detectContradiction("TLS 1.2", "replaces", "TLS 1.3");
        assertNotNull(contradiction);
        assertTrue(contradiction.contains("Contradiction"));
    }

    // ═══ Stats ═══

    @Test
    void statsNotEmpty() {
        String stats = engine.getStats();
        assertNotNull(stats);
        assertTrue(stats.contains("math facts"));
        assertTrue(stats.contains("relationships"));
    }
}
