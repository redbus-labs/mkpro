package com.mkpro.streaming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class AdkStreamHandlerTest {

    @Test
    @DisplayName("fromConsumer wraps onTextChunk calls properly")
    void testFromConsumer_wrapsOnTextChunk() {
        List<String> received = new ArrayList<>();
        AdkStreamHandler handler = AdkStreamHandler.fromConsumer(received::add);

        handler.onTextChunk("hello world");

        assertEquals(1, received.size());
        assertEquals("hello world", received.get(0));
    }

    @Test
    @DisplayName("fromConsumer handles null consumer and null text delta safely")
    void testFromConsumer_handlesNullsSafely() {
        AdkStreamHandler nullConsumerHandler = AdkStreamHandler.fromConsumer(null);
        assertDoesNotThrow(() -> nullConsumerHandler.onTextChunk("sample text"));

        List<String> received = new ArrayList<>();
        AdkStreamHandler handler = AdkStreamHandler.fromConsumer(received::add);
        handler.onTextChunk(null);

        assertTrue(received.isEmpty());
    }

    @Test
    @DisplayName("fromConsumer works when invoked via onChunk dispatch")
    void testFromConsumer_invokedViaOnChunk() {
        List<String> received = new ArrayList<>();
        AdkStreamHandler handler = AdkStreamHandler.fromConsumer(received::add);

        AdkStreamChunk chunk = AdkStreamChunk.text("s1", "a1", "chunk text");
        handler.onChunk(chunk);

        assertEquals(1, received.size());
        assertEquals("chunk text", received.get(0));
    }

    @Test
    @DisplayName("onChunk routes TEXT ChunkType case properly")
    void testOnChunk_routesTextChunk() {
        AtomicReference<String> textRef = new AtomicReference<>();
        AdkStreamHandler handler = new AdkStreamHandler() {
            @Override
            public void onTextChunk(String delta) {
                textRef.set(delta);
            }
        };

        AdkStreamChunk chunk = AdkStreamChunk.text("sess-1", "agent-1", "hello text");
        handler.onChunk(chunk);

        assertEquals("hello text", textRef.get());

        // Test with null textDelta
        AtomicBoolean calledOnNull = new AtomicBoolean(false);
        AdkStreamHandler nullTextHandler = new AdkStreamHandler() {
            @Override
            public void onTextChunk(String delta) {
                calledOnNull.set(true);
            }
        };
        nullTextHandler.onChunk(AdkStreamChunk.text("sess-1", "agent-1", null));
        assertFalse(calledOnNull.get());
    }

    @Test
    @DisplayName("onChunk routes TOOL_CALL ChunkType case properly")
    void testOnChunk_routesToolCallChunk() {
        AtomicReference<String> toolNameRef = new AtomicReference<>();
        AtomicReference<String> jsonDeltaRef = new AtomicReference<>();

        AdkStreamHandler handler = new AdkStreamHandler() {
            @Override
            public void onToolCallChunk(String toolName, String jsonDelta) {
                toolNameRef.set(toolName);
                jsonDeltaRef.set(jsonDelta);
            }
        };

        AdkStreamChunk chunk = AdkStreamChunk.toolCall("sess-1", "agent-1", "calculator", "{\"op\":\"add\"}");
        handler.onChunk(chunk);

        assertEquals("calculator", toolNameRef.get());
        assertEquals("{\"op\":\"add\"}", jsonDeltaRef.get());
    }

    @Test
    @DisplayName("onChunk routes ERROR ChunkType case properly")
    void testOnChunk_routesErrorChunk() {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        AdkStreamHandler handler = new AdkStreamHandler() {
            @Override
            public void onStreamError(Throwable error) {
                errorRef.set(error);
            }
        };

        IllegalStateException expectedException = new IllegalStateException("stream error occurred");
        AdkStreamChunk chunk = AdkStreamChunk.error("sess-1", "agent-1", expectedException);
        handler.onChunk(chunk);

        assertSame(expectedException, errorRef.get());

        // Test with null error
        AtomicBoolean calledOnNullError = new AtomicBoolean(false);
        AdkStreamHandler nullErrorHandler = new AdkStreamHandler() {
            @Override
            public void onStreamError(Throwable error) {
                calledOnNullError.set(true);
            }
        };
        nullErrorHandler.onChunk(AdkStreamChunk.error("sess-1", "agent-1", null));
        assertFalse(calledOnNullError.get());
    }

    @Test
    @DisplayName("onChunk handles null chunks, unhandled chunk types, or missing chunk types without exception")
    void testOnChunk_handlesNullAndOtherChunkTypes() {
        AdkStreamHandler handler = new AdkStreamHandler() {};

        assertDoesNotThrow(() -> handler.onChunk(null));
        assertDoesNotThrow(() -> handler.onChunk(AdkStreamChunk.audio("s1", "a1", new byte[]{1, 2, 3}, "audio/mp3")));
        assertDoesNotThrow(() -> handler.onChunk(AdkStreamChunk.metadata("s1", "a1", 10, 20)));
    }

    @Test
    @DisplayName("Default interface methods do not throw exceptions")
    void testDefaultMethods() {
        AdkStreamHandler handler = new AdkStreamHandler() {};

        assertDoesNotThrow(() -> {
            handler.onStreamStart("s1", "a1");
            handler.onTextChunk("test");
            handler.onToolCallChunk("tool", "{}");
            handler.onStreamComplete("s1", "res");
            handler.onStreamError(new Exception("test"));
        });
    }
}
