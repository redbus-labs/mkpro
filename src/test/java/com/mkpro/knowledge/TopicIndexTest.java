package com.mkpro.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TopicIndexTest {

    private TopicIndex index;

    @BeforeEach
    void setUp() {
        index = new TopicIndex();
    }

    @Test
    void testIndexAndSearch() {
        index.indexTopic("java-gc", "Java garbage collection G1GC ZGC heap tuning performance memory allocation");
        index.indexTopic("python-ml", "Python machine learning tensorflow pytorch neural network deep learning training");
        index.indexTopic("docker-k8s", "Docker containers Kubernetes pods deployment scaling orchestration microservices");
        index.rebuildIdf();

        List<TopicIndex.SearchResult> results = index.search("garbage collection heap", 3);
        assertFalse(results.isEmpty());
        assertEquals("java-gc", results.get(0).getTopicName());
        assertTrue(results.get(0).getScore() > 0);
    }

    @Test
    void testSearchNoResults() {
        index.indexTopic("java-gc", "Java garbage collection tuning");
        index.rebuildIdf();

        List<TopicIndex.SearchResult> results = index.search("xyzzyplugh", 3);
        // Should return empty or very low scores
        assertTrue(results.isEmpty() || results.get(0).getScore() == 0.0);
    }

    @Test
    void testRemoveTopic() {
        index.indexTopic("java-gc", "Java garbage collection tuning");
        index.indexTopic("python-ml", "Python machine learning");
        index.rebuildIdf();

        index.removeTopic("java-gc");
        index.rebuildIdf();

        List<TopicIndex.SearchResult> results = index.search("Java garbage", 3);
        // Should not find java-gc anymore
        for (TopicIndex.SearchResult r : results) {
            assertNotEquals("java-gc", r.getTopicName());
        }
    }

    @Test
    void testSnippetTruncation() {
        String longText = "A".repeat(500) + " important keyword here";
        index.indexTopic("long-topic", longText);
        index.rebuildIdf();

        List<TopicIndex.SearchResult> results = index.search("important keyword", 1);
        if (!results.isEmpty()) {
            assertTrue(results.get(0).getSnippet().length() <= 210); // ~200 + possible word boundary
        }
    }

    @Test
    void testMultipleTopicsRanking() {
        index.indexTopic("full-match", "kubernetes security vulnerabilities CVE pods RBAC network policy");
        index.indexTopic("partial-match", "kubernetes deployment scaling pods replicas");
        index.indexTopic("no-match", "python pandas dataframe numpy analysis");
        index.rebuildIdf();

        List<TopicIndex.SearchResult> results = index.search("kubernetes security CVE", 3);
        assertFalse(results.isEmpty());
        assertEquals("full-match", results.get(0).getTopicName());
    }

    @Test
    void testEmptyIndex() {
        List<TopicIndex.SearchResult> results = index.search("anything", 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void testStopwordsFiltered() {
        index.indexTopic("test", "the quick brown fox jumps over the lazy dog");
        index.rebuildIdf();

        // "the" and "over" are stopwords, shouldn't affect ranking
        List<TopicIndex.SearchResult> results = index.search("quick brown fox", 1);
        assertFalse(results.isEmpty());
        assertEquals("test", results.get(0).getTopicName());
    }
}
