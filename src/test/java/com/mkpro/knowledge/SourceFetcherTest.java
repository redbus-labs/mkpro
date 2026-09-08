package com.mkpro.knowledge;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SourceFetcher — URL validation, error handling, fetchAll.
 * Note: These tests hit invalid/nonexistent URLs to verify error handling,
 * not actual remote servers (to avoid network dependency in CI).
 */
public class SourceFetcherTest {

    private SourceFetcher fetcher;

    @BeforeEach
    void setUp() {
        fetcher = new SourceFetcher();
    }

    @Test
    void invalidUrlReturnsError() {
        String result = fetcher.fetch("not-a-valid-url");
        assertNotNull(result);
        assertTrue(result.contains("[FETCH ERROR:"));
    }

    @Test
    void unreachableHostReturnsError() {
        String result = fetcher.fetch("http://192.0.2.1:1"); // RFC 5737 TEST-NET — unreachable
        assertNotNull(result);
        assertTrue(result.contains("[FETCH ERROR:"));
    }

    @Test
    void nullHandledGracefully() {
        // Fetch null — should return error string, not crash
        String result = fetcher.fetch(null);
        assertNotNull(result);
        assertTrue(result.contains("[FETCH ERROR:") || result.contains("null"));
    }

    @Test
    void fetchAllWithMultipleUrls() {
        Map<String, String> results = fetcher.fetchAll(List.of(
            "http://invalid-nonexistent-domain-xyz.test/page1",
            "http://invalid-nonexistent-domain-xyz.test/page2"
        ));

        assertEquals(2, results.size());
        for (String value : results.values()) {
            assertTrue(value.contains("[FETCH ERROR:"));
        }
    }

    @Test
    void fetchAllNullList() {
        Map<String, String> results = fetcher.fetchAll(null);
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void fetchAllEmptyList() {
        Map<String, String> results = fetcher.fetchAll(List.of());
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void malformedUrlReturnsError() {
        String result = fetcher.fetch("htp://missing-scheme.com");
        assertNotNull(result);
        assertTrue(result.contains("[FETCH ERROR:"));
    }

    @Test
    void resultContainsUrlInError() {
        String url = "http://this-does-not-exist-at-all.invalid/path";
        String result = fetcher.fetch(url);
        assertTrue(result.contains(url));
    }
}
