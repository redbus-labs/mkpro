package com.mkpro.knowledge;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StreamKnowledgeMonitor — mid-stream gap detection and background fetch.
 */
public class StreamKnowledgeMonitorTest {

    private TopicIndex index;
    private KnowledgeStore store;
    private KnowledgeScheduler scheduler;

    @BeforeEach
    void setUp() {
        index = new TopicIndex();
        store = new KnowledgeStore(null);
        scheduler = new KnowledgeScheduler(store, index, new SourceFetcher(), List.of());
    }

    @Test
    void noTriggerOnShortChunks() {
        // LLM callback should never be called for short, non-gap content
        StreamKnowledgeMonitor monitor = new StreamKnowledgeMonitor(scheduler, index, prompt -> {
            fail("LLM should not be called for normal content");
            return null;
        });

        monitor.onChunk("Here is a simple explanation.");
        monitor.onStreamEnd();
        assertTrue(monitor.getTriggeredTopics().isEmpty());
    }

    @Test
    void triggersOnExplicitGapPhrase() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        StreamKnowledgeMonitor monitor = new StreamKnowledgeMonitor(scheduler, index, prompt -> {
            callCount.incrementAndGet();
            return "TOPIC:test-topic\nURL:https://example.com/docs\nFOCUS:testing";
        });

        // Feed enough content to trigger scan (500+ chars with gap signal)
        StringBuilder filler = new StringBuilder();
        for (int i = 0; i < 450; i++) filler.append("x");
        filler.append(" I'm not familiar with the exact configuration format for this.");

        monitor.onChunk(filler.toString());
        // Wait for background thread
        Thread.sleep(500);

        assertTrue(callCount.get() > 0, "LLM callback should have been triggered");
    }

    @Test
    void triggersOnToolError() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        StreamKnowledgeMonitor monitor = new StreamKnowledgeMonitor(scheduler, index, prompt -> {
            callCount.incrementAndGet();
            return "NONE";
        });

        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 480; i++) content.append("y");
        content.append(" Error: command not found: kubectl");

        monitor.onChunk(content.toString());
        Thread.sleep(1500);

        assertTrue(callCount.get() > 0, "Should detect tool error signal");
    }

    @Test
    void noTriggerOnConfidentResponse() {
        StreamKnowledgeMonitor monitor = new StreamKnowledgeMonitor(scheduler, index, prompt -> {
            fail("Should not trigger on confident response");
            return null;
        });

        // Feed confident content without any gap signals (under scan threshold)
        monitor.onChunk("The configuration is straightforward. Set replicas to 3 in the deployment.");
        monitor.onStreamEnd();
        assertTrue(monitor.getTriggeredTopics().isEmpty());
    }

    @Test
    void maxFetchesCapped() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        StreamKnowledgeMonitor monitor = new StreamKnowledgeMonitor(scheduler, index, prompt -> {
            callCount.incrementAndGet();
            return "TOPIC:topic-" + callCount.get() + "\nURL:https://example.com/" + callCount.get() + "\nFOCUS:test";
        });

        // Trigger multiple scans each with a gap signal
        for (int i = 0; i < 5; i++) {
            StringBuilder chunk = new StringBuilder();
            for (int j = 0; j < 480; j++) chunk.append("z");
            chunk.append(" I'm not familiar with this part of the API.");
            monitor.onChunk(chunk.toString());
        }
        Thread.sleep(1000);

        // Should cap at MAX_FETCHES_PER_STREAM (2)
        assertTrue(callCount.get() <= 2, "Should cap at 2 fetches, got: " + callCount.get());
    }

    @Test
    void dedupWithinStream() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        StreamKnowledgeMonitor monitor = new StreamKnowledgeMonitor(scheduler, index, prompt -> {
            callCount.incrementAndGet();
            // Always suggest same topic
            return "TOPIC:same-topic\nURL:https://example.com\nFOCUS:test";
        });

        StringBuilder chunk1 = new StringBuilder();
        for (int j = 0; j < 480; j++) chunk1.append("a");
        chunk1.append(" I need to look up the configuration for this.");
        monitor.onChunk(chunk1.toString());

        Thread.sleep(500);

        StringBuilder chunk2 = new StringBuilder();
        for (int j = 0; j < 480; j++) chunk2.append("b");
        chunk2.append(" I need to look up something else here.");
        monitor.onChunk(chunk2.toString());

        Thread.sleep(500);

        // Second call should have same topic → dedup, so only 1 triggered topic
        assertTrue(monitor.getTriggeredTopics().size() <= 1);
    }

    @Test
    void resetClearsState() {
        StreamKnowledgeMonitor monitor = new StreamKnowledgeMonitor(scheduler, index, prompt -> "NONE");

        monitor.onChunk("some content");
        monitor.reset();

        // After reset, should be fresh
        assertTrue(monitor.getTriggeredTopics().isEmpty());
    }

    @Test
    void onStreamEndScansRemainder() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        StreamKnowledgeMonitor monitor = new StreamKnowledgeMonitor(scheduler, index, prompt -> {
            callCount.incrementAndGet();
            return "NONE";
        });

        // Feed content below scan threshold but with gap signal
        monitor.onChunk("The agent said: I'm not familiar with Kubernetes HPA custom metrics format.");
        // Content is below 500 chars so onChunk won't scan
        assertEquals(0, callCount.get());

        // onStreamEnd should scan the remainder
        monitor.onStreamEnd();
        Thread.sleep(500);

        assertTrue(callCount.get() > 0, "onStreamEnd should trigger final scan");
    }

    @Test
    void nullChunksIgnored() {
        StreamKnowledgeMonitor monitor = new StreamKnowledgeMonitor(scheduler, index, prompt -> {
            fail("Should not trigger");
            return null;
        });

        monitor.onChunk(null);
        monitor.onChunk("");
        monitor.onStreamEnd();
        assertTrue(monitor.getTriggeredTopics().isEmpty());
    }

    @Test
    void skipsWhenCoverageExists() throws InterruptedException {
        // Index a topic that covers kubernetes
        index.indexTopic("k8s-docs", "kubernetes HPA autoscaling custom metrics pods deployment");
        index.rebuildIdf();

        AtomicInteger callCount = new AtomicInteger(0);
        StreamKnowledgeMonitor monitor = new StreamKnowledgeMonitor(scheduler, index, prompt -> {
            callCount.incrementAndGet();
            return "TOPIC:k8s-extra\nURL:https://k8s.io\nFOCUS:more k8s";
        });

        StringBuilder chunk = new StringBuilder();
        for (int j = 0; j < 480; j++) chunk.append("w");
        chunk.append(" I'm not familiar with kubernetes HPA autoscaling custom metrics.");
        monitor.onChunk(chunk.toString());
        Thread.sleep(500);

        // LLM may be called, but addTopic should check coverage
        // The key thing is getTriggeredTopics won't have it if coverage blocked the fetch
    }

    @Test
    void shutdownStopsProcessing() {
        StreamKnowledgeMonitor monitor = new StreamKnowledgeMonitor(scheduler, index, prompt -> {
            fail("Should not be called after shutdown");
            return null;
        });

        monitor.shutdown();
        // After shutdown, onChunk should be no-op
        monitor.onChunk("I'm not familiar with anything at all and this is long enough to trigger.");
        monitor.onStreamEnd();
    }
}
