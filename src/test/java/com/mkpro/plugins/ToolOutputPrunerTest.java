package com.mkpro.plugins;

import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolOutputPrunerTest {

    @Test
    void testPruneTextShort() {
        String text = "Short text";
        assertEquals(text, ToolOutputPruner.pruneText(text, 50));
    }

    @Test
    void testPruneTextLong() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("0123456789");
        }
        String longText = sb.toString(); // 1000 chars
        String pruned = ToolOutputPruner.pruneText(longText, 200);

        assertTrue(pruned.contains(ToolOutputPruner.TRUNCATION_MARKER_PREFIX));
        assertTrue(pruned.length() < longText.length());
    }

    @Test
    void testPrunePayloadObjectMap() {
        Map<String, Object> map = new HashMap<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append("abcdefghij");
        map.put("key1", sb.toString());
        map.put("key2", "short");

        @SuppressWarnings("unchecked")
        Map<String, Object> pruned = (Map<String, Object>) ToolOutputPruner.prunePayloadObject(map, 100);

        assertNotNull(pruned);
        assertEquals("short", pruned.get("key2"));
        assertTrue(((String) pruned.get("key1")).contains(ToolOutputPruner.TRUNCATION_MARKER_PREFIX));
    }

    @Test
    void testPruneContentFunctionResponse() {
        Map<String, Object> respMap = new HashMap<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append("data-payload-");
        respMap.put("output", sb.toString());

        FunctionResponse fnResp = FunctionResponse.builder()
                .name("test_tool")
                .response(respMap)
                .build();

        Content content = Content.builder()
                .role("tool")
                .parts(List.of(Part.builder().functionResponse(fnResp).build()))
                .build();

        Content prunedContent = ToolOutputPruner.pruneContent(content, 150);
        assertNotNull(prunedContent);
        assertTrue(prunedContent.parts().isPresent());
        FunctionResponse prunedFn = prunedContent.parts().get().get(0).functionResponse().get();
        Map<String, Object> prunedResp = prunedFn.response().get();
        assertTrue(((String) prunedResp.get("output")).contains(ToolOutputPruner.TRUNCATION_MARKER_PREFIX));
    }
}
