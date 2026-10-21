package com.mkpro.streaming;

import java.util.function.Consumer;

/**
 * Handler interface for receiving live streaming events and chunks from ADK streams.
 */
public interface AdkStreamHandler {

    /**
     * Called when a stream starts.
     */
    default void onStreamStart(String sessionId, String agentName) {}

    /**
     * Called when a text delta chunk is received.
     */
    default void onTextChunk(String delta) {}

    /**
     * Called when a tool call chunk is received.
     */
    default void onToolCallChunk(String toolName, String jsonDelta) {}

    /**
     * Called when a stream completes normally.
     */
    default void onStreamComplete(String sessionId, String fullResponse) {}

    /**
     * Called when a stream fails with an error.
     */
    default void onStreamError(Throwable error) {}

    /**
     * Default dispatcher handling different ChunkType cases.
     */
    default void onChunk(AdkStreamChunk chunk) {
        if (chunk == null || chunk.getChunkType() == null) {
            return;
        }
        switch (chunk.getChunkType()) {
            case TEXT:
                if (chunk.getTextDelta() != null) {
                    onTextChunk(chunk.getTextDelta());
                }
                break;
            case TOOL_CALL:
                onToolCallChunk(chunk.getToolName(), chunk.getJsonDelta());
                break;
            case ERROR:
                if (chunk.getError() != null) {
                    onStreamError(chunk.getError());
                }
                break;
            case AUDIO:
            case METADATA:
            case MULTIMODAL:
            default:
                break;
        }
    }

    /**
     * Wraps a legacy Consumer<String> text chunk callback into an AdkStreamHandler.
     */
    static AdkStreamHandler fromConsumer(Consumer<String> consumer) {
        return new AdkStreamHandler() {
            @Override
            public void onTextChunk(String delta) {
                if (consumer != null && delta != null) {
                    consumer.accept(delta);
                }
            }
        };
    }
}
