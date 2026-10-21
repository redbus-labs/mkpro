package com.mkpro.streaming;

import java.util.Arrays;

/**
 * Immutable data model representing a chunk of streaming data from or to an ADK live stream.
 */
public final class AdkStreamChunk {

    public enum ChunkType {
        TEXT,
        TOOL_CALL,
        AUDIO,
        MULTIMODAL,
        METADATA,
        ERROR
    }

    private final ChunkType chunkType;
    private final String sessionId;
    private final String agentName;
    private final String textDelta;
    private final String toolName;
    private final String jsonDelta;
    private final byte[] audioData;
    private final String mimeType;
    private final int promptTokens;
    private final int candidateTokens;
    private final int totalTokens;
    private final long timestamp;
    private final Throwable error;

    private AdkStreamChunk(
            ChunkType chunkType,
            String sessionId,
            String agentName,
            String textDelta,
            String toolName,
            String jsonDelta,
            byte[] audioData,
            String mimeType,
            int promptTokens,
            int candidateTokens,
            int totalTokens,
            long timestamp,
            Throwable error) {
        this.chunkType = chunkType;
        this.sessionId = sessionId;
        this.agentName = agentName;
        this.textDelta = textDelta;
        this.toolName = toolName;
        this.jsonDelta = jsonDelta;
        this.audioData = audioData != null ? Arrays.copyOf(audioData, audioData.length) : null;
        this.mimeType = mimeType;
        this.promptTokens = promptTokens;
        this.candidateTokens = candidateTokens;
        this.totalTokens = totalTokens;
        this.timestamp = timestamp;
        this.error = error;
    }

    // Factory methods
    public static AdkStreamChunk text(String sessionId, String agentName, String delta) {
        return new AdkStreamChunk(
                ChunkType.TEXT, sessionId, agentName, delta,
                null, null, null, null,
                0, 0, 0, System.currentTimeMillis(), null
        );
    }

    public static AdkStreamChunk toolCall(String sessionId, String agentName, String toolName, String jsonDelta) {
        return new AdkStreamChunk(
                ChunkType.TOOL_CALL, sessionId, agentName, null,
                toolName, jsonDelta, null, null,
                0, 0, 0, System.currentTimeMillis(), null
        );
    }

    public static AdkStreamChunk audio(String sessionId, String agentName, byte[] audioData, String mimeType) {
        return new AdkStreamChunk(
                ChunkType.AUDIO, sessionId, agentName, null,
                null, null, audioData, mimeType,
                0, 0, 0, System.currentTimeMillis(), null
        );
    }

    public static AdkStreamChunk metadata(String sessionId, String agentName, int promptTokens, int candidateTokens) {
        return new AdkStreamChunk(
                ChunkType.METADATA, sessionId, agentName, null,
                null, null, null, null,
                promptTokens, candidateTokens, promptTokens + candidateTokens, System.currentTimeMillis(), null
        );
    }

    public static AdkStreamChunk error(String sessionId, String agentName, Throwable error) {
        return new AdkStreamChunk(
                ChunkType.ERROR, sessionId, agentName, null,
                null, null, null, null,
                0, 0, 0, System.currentTimeMillis(), error
        );
    }

    // Getters
    public ChunkType getChunkType() {
        return chunkType;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getAgentName() {
        return agentName;
    }

    public String getTextDelta() {
        return textDelta;
    }

    public String getToolName() {
        return toolName;
    }

    public String getJsonDelta() {
        return jsonDelta;
    }

    public byte[] getAudioData() {
        return audioData != null ? Arrays.copyOf(audioData, audioData.length) : null;
    }

    public String getMimeType() {
        return mimeType;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCandidateTokens() {
        return candidateTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Throwable getError() {
        return error;
    }

    @Override
    public String toString() {
        return "AdkStreamChunk{" +
                "type=" + chunkType +
                ", sessionId='" + sessionId + '\'' +
                ", agentName='" + agentName + '\'' +
                ", textDelta='" + textDelta + '\'' +
                ", toolName='" + toolName + '\'' +
                ", promptTokens=" + promptTokens +
                ", candidateTokens=" + candidateTokens +
                ", totalTokens=" + totalTokens +
                ", timestamp=" + timestamp +
                '}';
    }
}
