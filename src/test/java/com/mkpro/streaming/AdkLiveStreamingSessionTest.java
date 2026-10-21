package com.mkpro.streaming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class AdkLiveStreamingSessionTest {

    @Test
    @DisplayName("Constructor and initial session state initialization")
    void testConstructorAndInitialState() {
        AdkLiveStreamingSession session = new AdkLiveStreamingSession("session-100", "agent-alpha");

        assertEquals("session-100", session.getSessionId());
        assertEquals("agent-alpha", session.getAgentName());
        assertEquals(AdkLiveStreamingSession.State.INITIALIZED, session.getState());
        assertEquals("", session.getFullResponse());
        assertEquals(0, session.getPromptTokens());
        assertEquals(0, session.getCandidateTokens());
        assertEquals(0, session.getTotalTokens());
        assertEquals(0L, session.getStartTime());
        assertEquals(0L, session.getEndTime());
        assertNull(session.getError());
        assertTrue(session.getHandlers().isEmpty());

        // Test single parameter constructor
        AdkLiveStreamingSession defaultAgentSession = new AdkLiveStreamingSession("session-200");
        assertEquals("session-200", defaultAgentSession.getSessionId());
        assertEquals("default", defaultAgentSession.getAgentName());

        // Test null parameter handling
        AdkLiveStreamingSession nullSession = new AdkLiveStreamingSession(null, null);
        assertEquals("", nullSession.getSessionId());
        assertEquals("", nullSession.getAgentName());
    }

    @Test
    @DisplayName("startStream updates state and notifies registered handlers")
    void testStartStream() {
        AdkLiveStreamingSession session = new AdkLiveStreamingSession("sess-1", "agent-1");

        AtomicBoolean startNotified = new AtomicBoolean(false);
        AtomicReference<String> sessRef = new AtomicReference<>();
        AtomicReference<String> agentRef = new AtomicReference<>();

        session.registerHandler(new AdkStreamHandler() {
            @Override
            public void onStreamStart(String sessionId, String agentName) {
                startNotified.set(true);
                sessRef.set(sessionId);
                agentRef.set(agentName);
            }
        });

        session.startStream();

        assertEquals(AdkLiveStreamingSession.State.STREAMING, session.getState());
        assertTrue(session.getStartTime() > 0);
        assertTrue(startNotified.get());
        assertEquals("sess-1", sessRef.get());
        assertEquals("agent-1", agentRef.get());

        // Calling startStream again should be a no-op
        long initialStartTime = session.getStartTime();
        startNotified.set(false);
        session.startStream();
        assertFalse(startNotified.get());
        assertEquals(initialStartTime, session.getStartTime());
    }

    @Test
    @DisplayName("appendTextDelta updates text buffer and dispatches text chunks")
    void testAppendTextDelta() {
        AdkLiveStreamingSession session = new AdkLiveStreamingSession("sess-1", "agent-1");
        List<AdkStreamChunk> chunks = new ArrayList<>();

        session.registerHandler(new AdkStreamHandler() {
            @Override
            public void onChunk(AdkStreamChunk chunk) {
                chunks.add(chunk);
            }
        });

        // Appending text while INITIALIZED auto-starts stream
        session.appendTextDelta("Hello ");
        assertEquals(AdkLiveStreamingSession.State.STREAMING, session.getState());

        session.appendTextDelta("World!");

        assertEquals("Hello World!", session.getFullResponse());
        assertEquals(2, chunks.size());
        assertEquals(AdkStreamChunk.ChunkType.TEXT, chunks.get(0).getChunkType());
        assertEquals("Hello ", chunks.get(0).getTextDelta());
        assertEquals("World!", chunks.get(1).getTextDelta());

        // Appending null should be ignored
        session.appendTextDelta(null);
        assertEquals("Hello World!", session.getFullResponse());
        assertEquals(2, chunks.size());
    }

    @Test
    @DisplayName("appendToolDelta aggregates deltas per tool and dispatches tool call chunks")
    void testAppendToolDelta() {
        AdkLiveStreamingSession session = new AdkLiveStreamingSession("sess-1", "agent-1");
        List<AdkStreamChunk> chunks = new ArrayList<>();

        session.registerHandler(new AdkStreamHandler() {
            @Override
            public void onChunk(AdkStreamChunk chunk) {
                chunks.add(chunk);
            }
        });

        session.appendToolDelta("calculator", "{\"op\":\"add\"}");
        session.appendToolDelta("calculator", ",{\"arg\":1}");
        session.appendToolDelta("search", "{\"q\":\"test\"}");

        Map<String, String> toolDeltas = session.getToolInvocationDeltas();
        assertEquals("{\"op\":\"add\"},{\"arg\":1}", toolDeltas.get("calculator"));
        assertEquals("{\"q\":\"test\"}", toolDeltas.get("search"));
        assertEquals(3, chunks.size());
        assertEquals(AdkStreamChunk.ChunkType.TOOL_CALL, chunks.get(0).getChunkType());

        // Test null tool name falls back to 'unknown'
        session.appendToolDelta(null, "{\"data\":1}");
        assertTrue(session.getToolInvocationDeltas().containsKey("unknown"));

        // Test null json delta appends empty string
        session.appendToolDelta("weather", null);
        assertEquals("", session.getToolInvocationDeltas().get("weather"));
    }

    @Test
    @DisplayName("updateTokens updates metrics and dispatches metadata chunk")
    void testUpdateTokens() {
        AdkLiveStreamingSession session = new AdkLiveStreamingSession("sess-1", "agent-1");
        AtomicReference<AdkStreamChunk> metadataChunkRef = new AtomicReference<>();

        session.registerHandler(new AdkStreamHandler() {
            @Override
            public void onChunk(AdkStreamChunk chunk) {
                if (chunk.getChunkType() == AdkStreamChunk.ChunkType.METADATA) {
                    metadataChunkRef.set(chunk);
                }
            }
        });

        session.updateTokens(150, 75);

        assertEquals(150, session.getPromptTokens());
        assertEquals(75, session.getCandidateTokens());
        assertEquals(225, session.getTotalTokens());

        assertNotNull(metadataChunkRef.get());
        assertEquals(150, metadataChunkRef.get().getPromptTokens());
        assertEquals(75, metadataChunkRef.get().getCandidateTokens());
        assertEquals(225, metadataChunkRef.get().getTotalTokens());
    }

    @Test
    @DisplayName("completeStream updates state, sets endTime, and notifies complete handlers")
    void testCompleteStream() {
        AdkLiveStreamingSession session = new AdkLiveStreamingSession("sess-1", "agent-1");
        AtomicBoolean completeNotified = new AtomicBoolean(false);
        AtomicReference<String> finalResponseRef = new AtomicReference<>();

        session.registerHandler(new AdkStreamHandler() {
            @Override
            public void onStreamComplete(String sessionId, String fullResponse) {
                completeNotified.set(true);
                finalResponseRef.set(fullResponse);
            }
        });

        session.appendTextDelta("Completed Response");
        session.completeStream();

        assertEquals(AdkLiveStreamingSession.State.COMPLETED, session.getState());
        assertTrue(session.getEndTime() >= session.getStartTime());
        assertTrue(completeNotified.get());
        assertEquals("Completed Response", finalResponseRef.get());

        // Subsequent text deltas should be ignored after completion
        session.appendTextDelta(" Should ignore");
        assertEquals("Completed Response", session.getFullResponse());
    }

    @Test
    @DisplayName("failStream updates state to FAILED, captures error, and dispatches error chunk")
    void testFailStream() {
        AdkLiveStreamingSession session = new AdkLiveStreamingSession("sess-1", "agent-1");
        AtomicReference<Throwable> dispatchedError = new AtomicReference<>();

        session.registerHandler(new AdkStreamHandler() {
            @Override
            public void onChunk(AdkStreamChunk chunk) {
                if (chunk.getChunkType() == AdkStreamChunk.ChunkType.ERROR) {
                    dispatchedError.set(chunk.getError());
                }
            }
        });

        session.startStream();
        IllegalStateException expectedError = new IllegalStateException("Stream failure simulated");
        session.failStream(expectedError);

        assertEquals(AdkLiveStreamingSession.State.FAILED, session.getState());
        assertSame(expectedError, session.getError());
        assertTrue(session.getEndTime() >= session.getStartTime());
        assertSame(expectedError, dispatchedError.get());

        // Text appends ignored after fail
        session.appendTextDelta("ignored text");
        assertEquals("", session.getFullResponse());
    }

    @Test
    @DisplayName("registerHandler and unregisterHandler manage subscriber callbacks correctly")
    void testRegisterAndUnregisterHandler() {
        AdkLiveStreamingSession session = new AdkLiveStreamingSession("sess-1", "agent-1");
        AtomicInteger h1Count = new AtomicInteger(0);
        AtomicInteger h2Count = new AtomicInteger(0);

        AdkStreamHandler h1 = new AdkStreamHandler() {
            @Override
            public void onChunk(AdkStreamChunk chunk) {
                h1Count.incrementAndGet();
            }
        };
        AdkStreamHandler h2 = new AdkStreamHandler() {
            @Override
            public void onChunk(AdkStreamChunk chunk) {
                h2Count.incrementAndGet();
            }
        };

        session.registerHandler(h1);
        session.registerHandler(h2);
        session.registerHandler(h1); // duplicate register

        assertEquals(2, session.getHandlers().size());

        session.appendTextDelta("delta 1");
        assertEquals(1, h1Count.get());
        assertEquals(1, h2Count.get());

        session.unregisterHandler(h1);
        assertEquals(1, session.getHandlers().size());

        session.appendTextDelta("delta 2");
        assertEquals(1, h1Count.get());
        assertEquals(2, h2Count.get());

        assertDoesNotThrow(() -> session.unregisterHandler(null));
        assertDoesNotThrow(() -> session.unregisterHandler(h1));
    }

    @Test
    @DisplayName("Handler exceptions do not interrupt session state or throw to caller")
    void testHandlerExceptionResilience() {
        AdkLiveStreamingSession session = new AdkLiveStreamingSession("sess-1", "agent-1");

        AdkStreamHandler faultyHandler = new AdkStreamHandler() {
            @Override
            public void onStreamStart(String sessionId, String agentName) {
                throw new IllegalStateException("Faulty start");
            }

            @Override
            public void onChunk(AdkStreamChunk chunk) {
                throw new IllegalStateException("Faulty chunk");
            }

            @Override
            public void onStreamComplete(String sessionId, String fullResponse) {
                throw new IllegalStateException("Faulty complete");
            }
        };

        session.registerHandler(faultyHandler);

        assertDoesNotThrow(() -> session.startStream());
        assertDoesNotThrow(() -> session.appendTextDelta("test text"));
        assertDoesNotThrow(() -> session.appendToolDelta("tool", "{}"));
        assertDoesNotThrow(() -> session.completeStream());

        assertEquals(AdkLiveStreamingSession.State.COMPLETED, session.getState());
        assertEquals("test text", session.getFullResponse());
    }
}
