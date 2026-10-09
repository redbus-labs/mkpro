package com.mkpro.plugins;

import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SmartEventFilterPluginTest {

    private static Content userContent(String text) {
        return Content.builder().role("user").parts(List.of(Part.fromText(text))).build();
    }

    private static Content modelContent(String text) {
        return Content.builder().role("model").parts(List.of(Part.fromText(text))).build();
    }

    @Test
    void testFilterConfigDefaults() {
        FilterConfig config = FilterConfig.defaults();
        assertEquals(10, config.getMaxTurns());
        assertTrue(config.isPruneToolOutputs());
        assertEquals(2000, config.getMaxToolPayloadChars());
        assertTrue(config.isEvictStaleToolChurn());
        assertTrue(config.isPinInitialPrompt());
        assertTrue(config.isPinMemoryEvents());

        // Prefix Cache Optimization Strategy defaults
        assertFalse(config.isSlidingWindowEnabled(), "slidingWindowEnabled must default to false");
        assertTrue(config.isSessionStartFilterOnly(), "sessionStartFilterOnly must default to true");
        assertFalse(config.isManualCompactionRequested(), "manualCompactionRequested must default to false");
        assertTrue(config.isPrefixCacheOptimized(), "isPrefixCacheOptimized() must be true by default");
    }

    @Test
    void testFilterConfigPrefixCacheOptimizationToggle() {
        FilterConfig config = FilterConfig.builder()
                .slidingWindowEnabled(true)
                .sessionStartFilterOnly(true)
                .build();
        assertFalse(config.isPrefixCacheOptimized());

        config.setSlidingWindowEnabled(false);
        config.setSessionStartFilterOnly(false);
        assertFalse(config.isPrefixCacheOptimized());

        config.setSessionStartFilterOnly(true);
        assertTrue(config.isPrefixCacheOptimized());

        config.requestManualCompaction();
        assertTrue(config.isManualCompactionRequested());
        config.setManualCompactionRequested(false);
        assertFalse(config.isManualCompactionRequested());
    }

    @Test
    void testFilterConfigConstructorsBackwardCompatibility() {
        FilterConfig c1 = new FilterConfig(5, true, 1000, true, true, true);
        assertEquals(5, c1.getMaxTurns());
        assertFalse(c1.isSlidingWindowEnabled());
        assertTrue(c1.isSessionStartFilterOnly());
        assertTrue(c1.isPrefixCacheOptimized());

        FilterConfig c2 = new FilterConfig(5, true, 1000, true, true, true, true, false);
        assertTrue(c2.isSlidingWindowEnabled());
        assertFalse(c2.isSessionStartFilterOnly());
        assertFalse(c2.isPrefixCacheOptimized());
    }

    @Test
    void testFilterContentsPinInitialPrompt() {
        Content c0 = userContent("Turn 0 user");
        Content c1 = modelContent("Turn 0 model");
        Content c2 = userContent("Turn 1 user");
        Content c3 = modelContent("Turn 1 model");
        Content c4 = userContent("Turn 2 user");
        Content c5 = modelContent("Turn 2 model");

        List<Content> contents = List.of(c0, c1, c2, c3, c4, c5);
        FilterConfig config = FilterConfig.builder()
                .maxTurns(1)
                .pinInitialPrompt(true)
                .evictStaleToolChurn(false)
                .build();

        List<Content> filtered = SmartEventFilterPlugin.filterContents(contents, config);
        assertTrue(filtered.contains(c0)); // Pinned initial prompt
        assertTrue(filtered.contains(c4)); // Active turn user
        assertTrue(filtered.contains(c5)); // Active turn model
        assertFalse(filtered.contains(c2)); // Outside window and not pinned
    }

    @Test
    void testPinMemoryEvents() {
        Content c0 = userContent("Turn 0 user");

        // Turn 1 with memory tool response
        FunctionResponse fnResp = FunctionResponse.builder()
                .name("memorize")
                .response(Map.of("data", "remember this"))
                .build();
        Content c1 = Content.builder()
                .role("tool")
                .parts(List.of(Part.builder().functionResponse(fnResp).build()))
                .build();
        Content c2 = userContent("Turn 2 user");
        Content c3 = modelContent("Turn 2 model");

        List<Content> contents = List.of(c0, c1, c2, c3);
        FilterConfig config = FilterConfig.builder()
                .maxTurns(1)
                .pinInitialPrompt(false)
                .pinMemoryEvents(true)
                .evictStaleToolChurn(false)
                .build();

        List<Content> filtered = SmartEventFilterPlugin.filterContents(contents, config);
        assertTrue(filtered.contains(c1)); // Memory event pinned even though outside turn window
    }

    @Test
    void testPluginCallback() {
        SmartEventFilterPlugin plugin = new SmartEventFilterPlugin(null);
        assertNotNull(plugin.getConfig());
        assertEquals("smart_event_filter_plugin", plugin.getName());
    }

    @Test
    void testSessionStartFilteringAndAppendOnlyPreservation() {
        FilterConfig config = FilterConfig.builder()
                .maxTurns(1)
                .pinInitialPrompt(false)
                .evictStaleToolChurn(false)
                .build();
        assertTrue(config.isPrefixCacheOptimized());

        SmartEventFilterPlugin plugin = new SmartEventFilterPlugin(config);
        assertFalse(plugin.isSessionStartFiltered());

        // Session start: simulate first model callback with 3 historical turns
        Content c0 = userContent("Turn 0 user");
        Content c1 = modelContent("Turn 0 model");
        Content c2 = userContent("Turn 1 user");
        Content c3 = modelContent("Turn 1 model");
        Content c4 = userContent("Turn 2 user");
        Content c5 = modelContent("Turn 2 model");
        List<Content> initialContents = List.of(c0, c1, c2, c3, c4, c5);

        LlmRequest.Builder reqBuilder1 = LlmRequest.builder().contents(initialContents);
        plugin.beforeModelCallback(null, reqBuilder1);

        assertTrue(plugin.isSessionStartFiltered(), "Plugin should mark session as filtered after first call");
        List<Content> filteredTurn1 = reqBuilder1.build().contents();
        // Since maxTurns=1 and pinInitialPrompt=false, Turn 0 and Turn 1 are truncated at session start
        assertFalse(filteredTurn1.contains(c0));
        assertFalse(filteredTurn1.contains(c2));
        assertTrue(filteredTurn1.contains(c4));
        assertTrue(filteredTurn1.contains(c5));

        // Subsequent turn during active conversation: append-only must be preserved!
        Content c6 = userContent("Turn 3 user");
        List<Content> turn2Contents = List.of(c4, c5, c6);
        LlmRequest.Builder reqBuilder2 = LlmRequest.builder().contents(turn2Contents);
        plugin.beforeModelCallback(null, reqBuilder2);

        List<Content> turn2Result = reqBuilder2.build().contents();
        // All contents must remain intact (no sliding window truncation of c4/c5!)
        assertEquals(3, turn2Result.size());
        assertTrue(turn2Result.contains(c4));
        assertTrue(turn2Result.contains(c5));
        assertTrue(turn2Result.contains(c6));
    }

    @Test
    void testManualCompactionTrigger() {
        FilterConfig config = FilterConfig.builder()
                .maxTurns(1)
                .pinInitialPrompt(false)
                .evictStaleToolChurn(false)
                .build();
        SmartEventFilterPlugin plugin = new SmartEventFilterPlugin(config);

        Content c0 = userContent("Turn 0");
        Content c1 = modelContent("Model 0");
        LlmRequest.Builder b1 = LlmRequest.builder().contents(List.of(c0, c1));
        plugin.beforeModelCallback(null, b1);
        assertTrue(plugin.isSessionStartFiltered());

        // Subsequent turns accumulate without truncation
        Content c2 = userContent("Turn 1");
        Content c3 = modelContent("Model 1");
        LlmRequest.Builder b2 = LlmRequest.builder().contents(List.of(c0, c1, c2, c3));
        plugin.beforeModelCallback(null, b2);
        assertEquals(4, b2.build().contents().size()); // Append-only preserved

        // Manual compaction requested (e.g. from /compact)
        plugin.requestManualCompaction();
        assertTrue(config.isManualCompactionRequested());

        // Next model call should compact down to maxTurns=1
        LlmRequest.Builder b3 = LlmRequest.builder().contents(List.of(c0, c1, c2, c3));
        plugin.beforeModelCallback(null, b3);

        List<Content> compacted = b3.build().contents();
        assertFalse(compacted.contains(c0)); // Compacted away
        assertTrue(compacted.contains(c2)); // In active window
        assertTrue(compacted.contains(c3));
        assertFalse(config.isManualCompactionRequested(), "Compaction flag should be cleared after run");

        // Subsequent turn continues append-only
        Content c4 = userContent("Turn 2");
        LlmRequest.Builder b4 = LlmRequest.builder().contents(List.of(c2, c3, c4));
        plugin.beforeModelCallback(null, b4);
        assertEquals(3, b4.build().contents().size());
    }

    @Test
    void testResetSessionState() {
        SmartEventFilterPlugin plugin = new SmartEventFilterPlugin(FilterConfig.defaults());
        assertFalse(plugin.isSessionStartFiltered());

        LlmRequest.Builder b = LlmRequest.builder().contents(List.of(userContent("hi")));
        plugin.beforeModelCallback(null, b);
        assertTrue(plugin.isSessionStartFiltered());

        plugin.resetSessionState();
        assertFalse(plugin.isSessionStartFiltered());
    }
}
