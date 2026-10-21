package com.mkpro.streaming;

import com.mkpro.events.MkProEvent;
import com.mkpro.events.MkProEventBus;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bi-directional live streaming client for ADK integration.
 * Manages connection state, handler registration, client text/audio delta emission,
 * server chunk reception, and event bus integration.
 */
public class AdkLiveClient {

    public enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        CLOSED
    }

    private ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private final String sessionId;
    private final String agentName;
    private MkProEventBus eventBus;
    private AdkLiveStreamingSession activeSession;

    private final List<AdkStreamHandler> handlers = new CopyOnWriteArrayList<>();

    public AdkLiveClient() {
        this("live-session-" + UUID.randomUUID().toString().substring(0, 8), "default", null);
    }

    public AdkLiveClient(String sessionId, String agentName) {
        this(sessionId, agentName, null);
    }

    public AdkLiveClient(String sessionId, String agentName, MkProEventBus eventBus) {
        this.sessionId = sessionId != null ? sessionId : "session-" + System.currentTimeMillis();
        this.agentName = agentName != null ? agentName : "default";
        this.eventBus = eventBus;
    }

    public synchronized void connect() {
        if (connectionState == ConnectionState.CONNECTED) {
            return;
        }
        this.connectionState = ConnectionState.CONNECTING;

        // Initialize active streaming session
        this.activeSession = new AdkLiveStreamingSession(sessionId, agentName);

        // Register client-level handlers on the session
        for (AdkStreamHandler handler : handlers) {
            activeSession.registerHandler(handler);
        }

        this.connectionState = ConnectionState.CONNECTED;
        activeSession.startStream();

        // Emit stream start event to MkProEventBus
        emitEvent(MkProEvent.streamStart(agentName, "adk-live"));
    }

    public synchronized void disconnect() {
        if (connectionState == ConnectionState.CLOSED || connectionState == ConnectionState.DISCONNECTED) {
            return;
        }
        if (activeSession != null && activeSession.getState() == AdkLiveStreamingSession.State.STREAMING) {
            activeSession.completeStream();
            emitEvent(MkProEvent.streamEnd());
        }
        this.connectionState = ConnectionState.DISCONNECTED;
    }

    public synchronized void close() {
        disconnect();
        this.connectionState = ConnectionState.CLOSED;
    }

    public boolean isConnected() {
        return connectionState == ConnectionState.CONNECTED;
    }

    public synchronized ConnectionState getConnectionState() {
        return connectionState;
    }

    public void registerHandler(AdkStreamHandler handler) {
        if (handler != null && !handlers.contains(handler)) {
            handlers.add(handler);
            if (activeSession != null) {
                activeSession.registerHandler(handler);
            }
        }
    }

    public void unregisterHandler(AdkStreamHandler handler) {
        if (handler != null) {
            handlers.remove(handler);
            if (activeSession != null) {
                activeSession.unregisterHandler(handler);
            }
        }
    }

    /**
     * Emits client text delta to server/session and publishes event.
     */
    public synchronized void emitClientTextDelta(String textDelta) {
        ensureConnected();
        if (textDelta == null) {
            return;
        }
        if (activeSession != null) {
            activeSession.appendTextDelta(textDelta);
        }
        emitEvent(MkProEvent.streamChunk(textDelta));
    }

    /**
     * Alias for emitClientTextDelta.
     */
    public void sendTextDelta(String textDelta) {
        emitClientTextDelta(textDelta);
    }

    /**
     * Emits client audio delta to server/session and notifies handlers.
     */
    public synchronized void emitClientAudioDelta(byte[] audioData, String mimeType) {
        ensureConnected();
        if (audioData == null) {
            return;
        }
        AdkStreamChunk audioChunk = AdkStreamChunk.audio(sessionId, agentName, audioData, mimeType);
        dispatchChunk(audioChunk);
    }

    /**
     * Alias for emitClientAudioDelta.
     */
    public void sendAudioDelta(byte[] audioData, String mimeType) {
        emitClientAudioDelta(audioData, mimeType);
    }

    /**
     * Receives a chunk from the server stream, updating session, notifying handlers,
     * and emitting appropriate events to MkProEventBus.
     */
    public synchronized void receiveServerChunk(AdkStreamChunk chunk) {
        if (chunk == null) {
            return;
        }
        ensureConnected();

        if (activeSession != null) {
            if (chunk.getChunkType() != null) {
                switch (chunk.getChunkType()) {
                    case TEXT:
                        activeSession.appendTextDelta(chunk.getTextDelta());
                        if (chunk.getTextDelta() != null) {
                            emitEvent(MkProEvent.streamChunk(chunk.getTextDelta()));
                        }
                        break;
                    case TOOL_CALL:
                        activeSession.appendToolDelta(chunk.getToolName(), chunk.getJsonDelta());
                        break;
                    case METADATA:
                        activeSession.updateTokens(chunk.getPromptTokens(), chunk.getCandidateTokens());
                        break;
                    case ERROR:
                        activeSession.failStream(chunk.getError());
                        emitEvent(MkProEvent.streamEnd());
                        break;
                    case AUDIO:
                    case MULTIMODAL:
                    default:
                        dispatchChunk(chunk);
                        break;
                }
            }
        } else {
            dispatchChunk(chunk);
        }
    }

    /**
     * Alias for receiveServerChunk.
     */
    public void receiveChunk(AdkStreamChunk chunk) {
        receiveServerChunk(chunk);
    }

    private void ensureConnected() {
        if (connectionState != ConnectionState.CONNECTED) {
            connect();
        }
    }

    private void dispatchChunk(AdkStreamChunk chunk) {
        for (AdkStreamHandler handler : handlers) {
            try {
                handler.onChunk(chunk);
            } catch (Exception e) {
                // Ignore listener failures
            }
        }
    }

    private void emitEvent(MkProEvent event) {
        MkProEventBus bus = getEventBus();
        if (bus != null && event != null) {
            bus.emit(event);
        }
    }

    public MkProEventBus getEventBus() {
        if (eventBus != null) {
            return eventBus;
        }
        return MkProEventBus.INSTANCE;
    }

    public void setEventBus(MkProEventBus eventBus) {
        this.eventBus = eventBus;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getAgentName() {
        return agentName;
    }

    public AdkLiveStreamingSession getActiveSession() {
        return activeSession;
    }

    public List<AdkStreamHandler> getHandlers() {
        return Collections.unmodifiableList(handlers);
    }
}
