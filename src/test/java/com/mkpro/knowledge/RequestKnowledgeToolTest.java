package com.mkpro.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RequestKnowledgeToolTest {

    @BeforeEach
    void setUp() {
        RequestKnowledgeTool.resetSessionCount();
    }

    @Test
    void testSchedulerContextPreventsRequests() {
        RequestKnowledgeTool.enterSchedulerContext();
        assertTrue(RequestKnowledgeTool.isInSchedulerContext());

        RequestKnowledgeTool.exitSchedulerContext();
        assertFalse(RequestKnowledgeTool.isInSchedulerContext());
    }

    @Test
    void testSessionCountTracking() {
        assertEquals(0, RequestKnowledgeTool.getSessionRequestCount());
        RequestKnowledgeTool.resetSessionCount();
        assertEquals(0, RequestKnowledgeTool.getSessionRequestCount());
    }

    @Test
    void testToolCreation() {
        var tool = RequestKnowledgeTool.create();
        assertNotNull(tool);
        assertEquals("request_knowledge", tool.name());
        assertTrue(tool.declaration().isPresent());
    }

    @Test
    void testCircularDependencyBlock() {
        // Init with null scheduler (simulates no scheduler)
        RequestKnowledgeTool.init(null, null);

        // Enter scheduler context
        RequestKnowledgeTool.enterSchedulerContext();

        try {
            var tool = RequestKnowledgeTool.create();
            Map<String, Object> args = Map.of(
                "topic", "test-topic",
                "description", "Need info about testing"
            );

            Map<String, Object> result = tool.runAsync(args, null).blockingGet();
            assertEquals("REJECTED", result.get("status"));
            assertTrue(result.get("reason").toString().contains("circular"));
        } finally {
            RequestKnowledgeTool.exitSchedulerContext();
        }
    }

    @Test
    void testSpamPrevention() {
        // Init with a fake scheduler that accepts topics
        var store = new KnowledgeSchedulerPhase2Test.FakeKnowledgeStore();
        var index = new TopicIndex();
        TopicConfig tc = new TopicConfig();
        tc.setName("existing");
        tc.setSources(List.of("http://example.com"));
        tc.setRefreshIntervalMinutes(60);
        var scheduler = new KnowledgeScheduler(store, index, new SourceFetcher(), List.of(tc));
        RequestKnowledgeTool.init(scheduler, store);
        RequestKnowledgeTool.resetSessionCount();

        var tool = RequestKnowledgeTool.create();

        // First 3 should succeed
        for (int i = 1; i <= 3; i++) {
            Map<String, Object> args = Map.of(
                "topic", "topic-" + i,
                "description", "Need info " + i
            );
            Map<String, Object> result = tool.runAsync(args, null).blockingGet();
            assertEquals("ACCEPTED", result.get("status"), "Request " + i + " should be accepted");
        }

        // 4th should be rejected
        Map<String, Object> args = Map.of(
            "topic", "topic-4",
            "description", "Need info 4"
        );
        Map<String, Object> result = tool.runAsync(args, null).blockingGet();
        assertEquals("REJECTED", result.get("status"));
        assertTrue(result.get("reason").toString().contains("Maximum"));
    }

    @Test
    void testDedupExistingTopic() {
        var store = new KnowledgeSchedulerPhase2Test.FakeKnowledgeStore();
        // Pre-populate a report
        TopicReport existing = new TopicReport("existing-topic", "Existing");
        existing.setSummary("This topic already has content accumulated.");
        store.saveReport(existing);

        RequestKnowledgeTool.init(null, store);
        RequestKnowledgeTool.resetSessionCount();

        var tool = RequestKnowledgeTool.create();
        Map<String, Object> args = Map.of(
            "topic", "existing-topic",
            "description", "I need this"
        );
        Map<String, Object> result = tool.runAsync(args, null).blockingGet();
        assertEquals("EXISTS", result.get("status"));
        assertNotNull(result.get("summary_preview"));
    }

    @Test
    void testNoSchedulerAvailable() {
        RequestKnowledgeTool.init(null, null);
        RequestKnowledgeTool.resetSessionCount();

        var tool = RequestKnowledgeTool.create();
        Map<String, Object> args = Map.of(
            "topic", "new-topic",
            "description", "Need info"
        );
        Map<String, Object> result = tool.runAsync(args, null).blockingGet();
        assertEquals("UNAVAILABLE", result.get("status"));
    }
}
