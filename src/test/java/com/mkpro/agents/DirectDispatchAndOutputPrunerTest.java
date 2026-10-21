package com.mkpro.agents;

import com.mkpro.plugins.ToolOutputPruner;
import com.mkpro.models.AgentRequest;
import com.mkpro.models.Provider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class DirectDispatchAndOutputPrunerTest {

    @Test
    void testToolOutputPrunerShortText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append("Short text content ");
        }
        String shortText = sb.toString();
        assertTrue(shortText.length() < 10000, "Text should be under 10,000 characters");

        String result = ToolOutputPruner.pruneText(shortText, 10000);
        assertEquals(shortText, result, "Text under 10,000 characters should not be modified");
    }

    @Test
    void testToolOutputPrunerLongText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1200; i++) {
            sb.append("Long text payload line ");
        }
        String longText = sb.toString();
        assertTrue(longText.length() > 10000, "Text should be over 10,000 characters");

        String result = ToolOutputPruner.pruneText(longText, 10000);
        assertNotEquals(longText, result, "Long text should be truncated");
        assertTrue(result.contains("[TRUNCATED"), "Truncated output should contain [TRUNCATED indicator");
        assertTrue(result.length() < longText.length(), "Truncated output length should be shorter than original");
    }

    @Test
    void testAgentRequestChunkConsumer() {
        AtomicReference<String> receivedChunk = new AtomicReference<>();
        Consumer<String> chunkConsumer = receivedChunk::set;

        AgentRequest request = new AgentRequest(
                "coder_agent",
                "Write code",
                "llama3",
                Provider.OLLAMA,
                "Create a feature",
                Collections.emptyList(),
                "http://localhost:11434",
                chunkConsumer
        );

        assertNotNull(request.getChunkConsumer(), "Chunk consumer should be stored");
        assertEquals(chunkConsumer, request.getChunkConsumer(), "Stored chunk consumer should match argument");

        request.getChunkConsumer().accept("Test streaming output chunk");
        assertEquals("Test streaming output chunk", receivedChunk.get(), "Chunk consumer should be invoked correctly");
    }

    @Test
    void testExecuteDirectDispatchNullHandling() {
        AgentManager agentManager = new AgentManager(
                null, null, null, null, null,
                null, null, null, null, null, null
        );

        String result = agentManager.executeDirectDispatch(null, null, null);
        assertNotNull(result, "Direct dispatch result should not be null");
        assertEquals("Error: Agent name and prompt must not be null.", result, "Direct dispatch should gracefully handle null inputs");
    }
}
