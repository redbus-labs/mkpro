package com.mkpro.routing;

import com.mkpro.core.MkProContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FastRouteEvaluatorTest {

    private MkProContext context;
    private MarkovRouter router;

    @BeforeEach
    void setUp() {
        context = new MkProContext();
        router = new MarkovRouter();
        context.setMarkovRouter(router);
    }

    @Test
    void testEmptyOrNullInput() {
        FastRouteEvaluator.EvaluationResult nullResult = FastRouteEvaluator.evaluate(null, context);
        assertFalse(nullResult.shouldDirectDispatch);

        FastRouteEvaluator.EvaluationResult emptyResult = FastRouteEvaluator.evaluate("", context);
        assertFalse(emptyResult.shouldDirectDispatch);
    }

    @Test
    void testNullContext() {
        FastRouteEvaluator.EvaluationResult result = FastRouteEvaluator.evaluate("some prompt", null);
        assertFalse(result.shouldDirectDispatch);
    }

    @Test
    void testCompoundTaskGuardrail() {
        String[] compoundPrompts = {
            "do step 1 then do step 2",
            "fix bug and then run tests",
            "clean project after that deploy",
            "first compile then test"
        };

        for (String prompt : compoundPrompts) {
            FastRouteEvaluator.EvaluationResult result = FastRouteEvaluator.evaluate(prompt, context);
            assertFalse(result.shouldDirectDispatch, "Expected no direct dispatch for: " + prompt);
            assertNotNull(result.reason);
            assertTrue(result.reason.toLowerCase().contains("compound"),
                "Expected reason to contain 'compound' for: " + prompt);
        }
    }

    @Test
    void testInsufficientObservations() {
        FastRouteEvaluator.EvaluationResult res0 = FastRouteEvaluator.evaluate("write unit test", context);
        assertFalse(res0.shouldDirectDispatch);

        for (int i = 0; i < 20; i++) {
            router.recordTransition(IntentClassifier.TaskCategory.TESTING, null, "Tester");
        }
        assertEquals(20, router.getTotalObservations());

        FastRouteEvaluator.EvaluationResult res20 = FastRouteEvaluator.evaluate("write unit test", context);
        assertFalse(res20.shouldDirectDispatch);
    }

    @Test
    void testDirectAgentKeywordMatch() {
        for (int i = 0; i < 25; i++) {
            router.recordTransition(IntentClassifier.TaskCategory.CODING, null, "Coder");
        }

        IntentClassifier.registerAgentKeywords("Coder", List.of("coder", "@coder"));

        FastRouteEvaluator.EvaluationResult result = FastRouteEvaluator.evaluate("@coder fix the bug", context);
        assertTrue(result.shouldDirectDispatch);
        assertEquals("Coder", result.targetAgent);
        assertEquals(1.0, result.confidence, 0.0001);
    }

    @Test
    void testHighConfidenceMarkovRouting() {
        for (int i = 0; i < 25; i++) {
            router.recordTransition(IntentClassifier.TaskCategory.TESTING, null, "Tester");
        }

        FastRouteEvaluator.EvaluationResult result = FastRouteEvaluator.evaluate("write unit tests for pytest assertion", context);
        assertTrue(result.shouldDirectDispatch);
        assertEquals("Tester", result.targetAgent);
        assertTrue(result.confidence >= 0.65);
    }
}
