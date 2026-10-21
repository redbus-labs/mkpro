package com.mkpro.streaming;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages state, buffers, metrics, and event dispatch for an active live streaming session.
 */
public class AdkLiveStreamingSession {

    public enum State {
        INITIALIZED,
        STREAMING,
        COMPLETED,
        FAILED
    }

    private final String sessionId;
    private final String agentName;
    private State state = State.INITIALIZED;

    private final StringBuilder fullTextBuffer = new StringBuilder();
    private final Map<String, StringBuilder> toolInvocationDeltas = new HashMap<>();

    private int promptTokens = 0;
    private int candidateTokens = 0;
    private int totalTokens = 0;

    private long startTime = 0L;
    private long endTime = 0L;
    private Throwable error = null;

    private final List<AdkStreamHandler> handlers = new CopyOnWriteArrayList<>();

    public AdkLiveStreamingSession(String sessionId, String agentName) {
        this.sessionId = sessionId != null ? sessionId : "";
        this.agentName = agentName != null ? agentName : "";
    }

    public AdkLiveStreamingSession(String sessionId) {
        this(sessionId, "default");
    }

    public void registerHandler(AdkStreamHandler handler) {
        if (handler != null && !handlers.contains(handler)) {
            handlers.add(handler);
        }
    }

    public void unregisterHandler(AdkStreamHandler handler) {
        if (handler != null) {
            handlers.remove(handler);
        }
    }

    public synchronized void startStream() {
        if (state == State.INITIALIZED) {
            this.state = State.STREAMING;
            this.startTime = System.currentTimeMillis();
            dispatchStreamStart();
        }
    }

    public synchronized void appendTextDelta(String delta) {
        if (state == State.INITIALIZED) {
            startStream();
        }
        if (state != State.STREAMING) {
            return;
        }
        if (delta != null) {
            fullTextBuffer.append(delta);
            AdkStreamChunk chunk = AdkStreamChunk.text(sessionId, agentName, delta);
            dispatchChunk(chunk);
        }
    }

    public synchronized void appendToolDelta(String toolName, String jsonDelta) {
        if (state == State.INITIALIZED) {
            startStream();
        }
        if (state != State.STREAMING) {
            return;
        }
        String key = toolName != null ? toolName : "unknown";
        toolInvocationDeltas.computeIfAbsent(key, k -> new StringBuilder())
                .append(jsonDelta != null ? jsonDelta : "");

        AdkStreamChunk chunk = AdkStreamChunk.toolCall(sessionId, agentName, toolName, jsonDelta);
        dispatchChunk(chunk);
    }

    public synchronized void updateTokens(int promptTokens, int candidateTokens) {
        this.promptTokens = promptTokens;
        this.candidateTokens = candidateTokens;
        this.totalTokens = promptTokens + candidateTokens;

        AdkStreamChunk chunk = AdkStreamChunk.metadata(sessionId, agentName, promptTokens, candidateTokens);
        dispatchChunk(chunk);
    }

    public synchronized void completeStream() {
        if (state == State.STREAMING || state == State.INITIALIZED) {
            this.state = State.COMPLETED;
            this.endTime = System.currentTimeMillis();
            dispatchStreamComplete(getFullResponse());
        }
    }

    public synchronized void failStream(Throwable error) {
        this.state = State.FAILED;
        this.error = error;
        this.endTime = System.currentTimeMillis();

        AdkStreamChunk chunk = AdkStreamChunk.error(sessionId, agentName, error);
        dispatchChunk(chunk);
    }

    private void dispatchStreamStart() {
        for (AdkStreamHandler handler : handlers) {
            try {
                handler.onStreamStart(sessionId, agentName);
            } catch (Exception e) {
                // Ignore handler exception
            }
        }
    }

    private void dispatchChunk(AdkStreamChunk chunk) {
        for (AdkStreamHandler handler : handlers) {
            try {
                handler.onChunk(chunk);
            } catch (Exception e) {
                // Ignore handler exception
            }
        }
    }

    private void dispatchStreamComplete(String fullResponse) {
        for (AdkStreamHandler handler : handlers) {
            try {
                handler.onStreamComplete(sessionId, fullResponse);
            } catch (Exception e) {
                // Ignore handler exception
            }
        }
    }

    // Getters
    public String getSessionId() {
        return sessionId;
    }

    public String getAgentName() {
        return agentName;
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized String getFullResponse() {
        return fullTextBuffer.toString();
    }

    public synchronized Map<String, String> getToolInvocationDeltas() {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, StringBuilder> entry : toolInvocationDeltas.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toString());
        }
        return Collections.unmodifiableMap(result);
    }

    public synchronized int getPromptTokens() {
        return promptTokens;
    }

    public synchronized int getCandidateTokens() {
        return candidateTokens;
    }

    public synchronized int getTotalTokens() {
        return totalTokens;
    }

    public synchronized long getStartTime() {
        return startTime;
    }

    public synchronized long getEndTime() {
        return endTime;
    }

    public synchronized Throwable getError() {
        return error;
    }

    public List<AdkStreamHandler> getHandlers() {
        return Collections.unmodifiableList(handlers);
    }
}
