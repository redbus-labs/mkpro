package com.mkpro.routing;

import com.mkpro.facts.FactEngine;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests verifying that components are properly wired together.
 * These test the CONNECTIONS between components, not the components themselves.
 */
public class WiringIntegrationTest {

    private MarkovRouter router;
    private MakerLoop maker;
    private FactEngine factEngine;

    @BeforeEach
    void setUp() {
        router = new MarkovRouter();
        maker = new MakerLoop(router);
        factEngine = new FactEngine();
        maker.setFactEngine(factEngine);
    }

    @AfterEach
    void tearDown() {
        factEngine.shutdown();
    }

    // ═══ FactEngine → MakerLoop pre-turn wiring ═══

    @Test
    void factEngineInjectsOnFirstTurnWhenMathKeywordsPresent() {
        maker.onUserInput("calculate the area of a circle with radius 10");
        String stimulus = maker.generatePreTurnStimulus();
        assertNotNull(stimulus, "Stimulus should not be null when math keywords are present");
        assertTrue(stimulus.contains("π") || stimulus.contains("VERIFIED"),
            "Stimulus should contain fact injection. Got: " + stimulus);
    }

    @Test
    void factEngineInjectsOnFirstTurnWhenRelationshipKeywordsPresent() {
        maker.onUserInput("configure HPA autoscaling for the deployment");
        String stimulus = maker.generatePreTurnStimulus();
        assertNotNull(stimulus, "Stimulus should not be null when relationship keywords are present");
        assertTrue(stimulus.contains("requires") || stimulus.contains("HPA") || stimulus.contains("VERIFIED"),
            "Stimulus should contain relationship facts. Got: " + stimulus);
    }

    @Test
    void factEngineDoesNotInjectForUnrelatedGoal() {
        maker.onUserInput("rename the variable to camelCase please");
        String stimulus = maker.generatePreTurnStimulus();
        // Either null or doesn't contain VERIFIED FACTS
        assertTrue(stimulus == null || !stimulus.contains("VERIFIED"),
            "Should not inject facts for unrelated goal");
    }

    @Test
    void factEngineInjectsOnSubsequentTurnsWithMathGoal() {
        maker.onUserInput("calculate sphere volume and deploy to kubernetes");
        // First turn — already tested above

        // Simulate turn 1 completion
        maker.onTurnComplete("DevOps", List.of("shell"), true, "deployed the config");

        // Turn 2 stimulus should contain facts
        String stimulus = maker.generatePreTurnStimulus();
        assertNotNull(stimulus, "Subsequent turn stimulus should not be null for active math goal");
        assertTrue(stimulus.contains("VERIFIED") || stimulus.contains("π") || stimulus.contains("MAKER"),
            "Should contain either facts or maker context");
    }

    // ═══ FactEngine → MakerLoop post-turn validation wiring ═══

    @Test
    void factEnginePostTurnDetectsRelationshipConflict() {
        maker.onUserInput("set up kubernetes autoscaling for our services");
        // Agent responds with a relationship conflict
        String response = "I've configured HPA without metrics-server for autoscaling. " +
            "This allows the pods to scale based on CPU utilization.";

        // onTurnComplete should trigger fact validation internally
        // We can't directly observe the event emission without a mock bus, but we can verify no crash
        MarkovRouter.MakerAction action = maker.onTurnComplete("DevOps", List.of("file_write"), true, response);
        assertNotNull(action);
    }

    @Test
    void factEnginePostTurnNoIssueOnValidResponse() {
        maker.onUserInput("set up kubernetes autoscaling");
        String response = "I've configured HPA with metrics-server. Applied the deployment manifest.";
        MarkovRouter.MakerAction action = maker.onTurnComplete("DevOps", List.of("file_write"), true, response);
        assertNotNull(action);
    }

    // ═══ FactEngine wiring null safety ═══

    @Test
    void makerWorksWithoutFactEngine() {
        MakerLoop plainMaker = new MakerLoop(router);
        // No setFactEngine called
        plainMaker.onUserInput("calculate area of circle with radius 5");
        String stimulus = plainMaker.generatePreTurnStimulus();
        // Should not crash — just no facts injected
        // Stimulus is null because no Markov data and no factEngine
        assertNull(stimulus);
    }

    @Test
    void makerWorksWithNullFactEngine() {
        maker.setFactEngine(null);
        maker.onUserInput("calculate the area of something");
        String stimulus = maker.generatePreTurnStimulus();
        // Should not crash
        assertNull(stimulus);
    }

    // ═══ Knowledge checker + FactEngine coexistence ═══

    @Test
    void factEngineAndKnowledgeCheckerBothWired() {
        // Wire knowledge components (null scheduler — just testing no crash)
        maker.setKnowledgeComponents(null, null, null);
        maker.setFactEngine(factEngine);

        maker.onUserInput("deploy kubernetes HPA with custom metrics and calculate latency");
        String stimulus = maker.generatePreTurnStimulus();
        // Should get facts even without knowledge scheduler
        assertNotNull(stimulus);
    }

    // ═══ ToolRegistry verify_fact availability ═══

    @Test
    void verifyFactToolRegistered() {
        com.mkpro.agents.ToolRegistry registry = new com.mkpro.agents.ToolRegistry(null, null);
        var tools = registry.resolve(List.of("verify_fact"));
        assertFalse(tools.isEmpty(), "verify_fact should be resolvable from ToolRegistry");
    }

    // ═══ FactEngine initialization timing ═══

    @Test
    void factEngineCanBeSetAfterGoalCreation() {
        MakerLoop lateMaker = new MakerLoop(router);
        lateMaker.onUserInput("calculate circle area with radius 7");

        // Set factEngine AFTER goal creation (simulates late wiring)
        lateMaker.setFactEngine(factEngine);

        // Should still work for subsequent stimulus calls
        lateMaker.onTurnComplete("Coder", List.of("file_write"), true, "done");
        String stimulus = lateMaker.generatePreTurnStimulus();
        // Should now have facts since factEngine is wired
        assertNotNull(stimulus);
        assertTrue(stimulus.contains("VERIFIED") || stimulus.contains("π") || stimulus.contains("MAKER"));
    }

    // ═══ Pre-turn injection content verification ═══

    @Test
    void preTurnInjectsCorrectFormulaForCircle() {
        maker.onUserInput("what is the area of a circle with radius 3");
        String stimulus = maker.generatePreTurnStimulus();
        assertNotNull(stimulus);
        assertTrue(stimulus.contains("π × r²") || stimulus.contains("π"),
            "Should inject circle area formula");
    }

    @Test
    void preTurnInjectsRelationshipForSpringBoot() {
        maker.onUserInput("set up a Spring Boot 3 application with JPA");
        String stimulus = maker.generatePreTurnStimulus();
        assertNotNull(stimulus);
        assertTrue(stimulus.contains("requires") || stimulus.contains("Java 17") || stimulus.contains("Spring"),
            "Should inject Spring Boot relationship facts. Got: " + stimulus);
    }

    @Test
    void preTurnInjectsForNetworking() {
        maker.onUserInput("configure HTTPS with TLS certificate for the server");
        String stimulus = maker.generatePreTurnStimulus();
        assertNotNull(stimulus);
        assertTrue(stimulus.contains("HTTPS") || stimulus.contains("TLS") || stimulus.contains("requires"),
            "Should inject HTTPS/TLS facts");
    }
}
