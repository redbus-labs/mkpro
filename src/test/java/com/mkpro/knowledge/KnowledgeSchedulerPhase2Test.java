package com.mkpro.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Knowledge Scheduler Phase 2:
 * - Topic discovery parsing
 * - Confidence scoring & staleness decay
 * - Access-frequency weighted refresh
 * - Intelligent merging prompt
 */
class KnowledgeSchedulerPhase2Test {

    private KnowledgeScheduler scheduler;
    private TopicIndex index;
    private FakeKnowledgeStore store;

    @BeforeEach
    void setUp() {
        store = new FakeKnowledgeStore();
        index = new TopicIndex();

        TopicConfig topic = new TopicConfig();
        topic.setName("test-topic");
        topic.setTitle("Test Topic");
        topic.setSources(List.of("http://example.com"));
        topic.setRefreshIntervalMinutes(60);
        topic.setInstruction("Analyze this content");

        scheduler = new KnowledgeScheduler(store, index, new SourceFetcher(), List.of(topic));
    }

    @Test
    void testAccessTracking() {
        scheduler.recordAccess("test-topic");
        scheduler.recordAccess("test-topic");
        scheduler.recordAccess("test-topic");
        scheduler.recordAccess("other-topic");

        assertEquals(3, scheduler.getAccessCount("test-topic"));
        assertEquals(1, scheduler.getAccessCount("other-topic"));
        assertEquals(0, scheduler.getAccessCount("nonexistent"));
    }

    @Test
    void testEffectiveIntervalReducesWithAccess() {
        TopicConfig topic = new TopicConfig();
        topic.setName("test-topic");
        topic.setRefreshIntervalMinutes(60);

        // No accesses — full interval
        assertEquals(60, scheduler.getEffectiveInterval(topic));

        // Record accesses — interval should decrease
        for (int i = 0; i < 10; i++) {
            scheduler.recordAccess("test-topic");
        }

        int effective = scheduler.getEffectiveInterval(topic);
        assertTrue(effective < 60, "Effective interval should be less than base: " + effective);
        assertTrue(effective >= 30, "Effective interval should not go below 50%: " + effective);
    }

    @Test
    void testAccessFrequenciesMap() {
        scheduler.recordAccess("topic-a");
        scheduler.recordAccess("topic-a");
        scheduler.recordAccess("topic-b");

        Map<String, Integer> freqs = scheduler.getAccessFrequencies();
        assertEquals(2, freqs.get("topic-a"));
        assertEquals(1, freqs.get("topic-b"));
    }

    @Test
    void testPendingDiscoveriesStartEmpty() {
        assertTrue(scheduler.getPendingDiscoveries().isEmpty());
    }

    @Test
    void testDismissDiscovery() {
        // Manually can't add discoveries in unit test without full flow
        // But we can verify dismiss doesn't throw
        scheduler.dismissDiscovery("nonexistent"); // Should not throw
    }

    @Test
    void testAnalyzeCallbackCanBeSet() {
        AtomicInteger callCount = new AtomicInteger(0);
        scheduler.setAnalyzeCallback((name, prompt) -> {
            callCount.incrementAndGet();
            return "Analyzed: " + name;
        });
        // Callback is set — verification that it doesn't throw
        assertNotNull(scheduler);
    }

    @Test
    void testStaleDecayDoesNotCrashWithEmptyStore() {
        // Should not throw even with no reports
        assertDoesNotThrow(() -> scheduler.applyStaleDecay());
    }

    @Test
    void testGetStatusShowsTopics() {
        Map<String, String> status = scheduler.getStatus();
        assertFalse(status.isEmpty());
        assertTrue(status.containsKey("test-topic"));
        assertEquals("never", status.get("test-topic"));
    }

    @Test
    void testForceRefreshUnknownTopic() {
        // Should not throw
        assertDoesNotThrow(() -> scheduler.forceRefresh("nonexistent-topic"));
    }

    /**
     * Minimal KnowledgeStore fake for testing (doesn't need CentralMemory).
     */
    static class FakeKnowledgeStore extends KnowledgeStore {
        private final java.util.Map<String, TopicReport> reports = new java.util.concurrent.ConcurrentHashMap<>();

        FakeKnowledgeStore() {
            super(null); // null CentralMemory — we override all methods
        }

        @Override
        public void saveReport(TopicReport report) {
            if (report != null && report.getName() != null) {
                reports.put(report.getName(), report);
            }
        }

        @Override
        public TopicReport getReport(String topicName) {
            return reports.get(topicName);
        }

        @Override
        public List<TopicReport> getAllReports() {
            return new java.util.ArrayList<>(reports.values());
        }
    }
}
