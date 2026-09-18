package com.mkpro.plugins;

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
}
