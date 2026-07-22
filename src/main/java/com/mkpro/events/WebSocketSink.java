package com.mkpro.events;

import com.mkpro.web.WebChatServer;

/**
 * WebSocket sink — formats MkProEvents as JSON and broadcasts to connected web clients.
 * Uses the existing WebChatServer broadcast infrastructure.
 */
public class WebSocketSink implements MkProEventListener {

    private final WebChatServer webChatServer;

    public WebSocketSink(WebChatServer webChatServer) {
        this.webChatServer = webChatServer;
    }

    @Override
    public void onEvent(MkProEvent event) {
        if (webChatServer == null) return;

        switch (event.getType()) {
            case ROUTING_DECISION -> {
                webChatServer.broadcastRouting("Fast-route → " + event.get("agent") +
                    " (" + event.get("confidence") + "% confidence, category: " + event.get("category") + ")");
            }
            case ROUTING_BELOW -> {
                webChatServer.broadcastRouting("Markov: " + event.get("agent") +
                    " " + event.get("confidence") + "% — below threshold, using Coordinator");
            }
            case ROUTING_INACTIVE -> {
                webChatServer.broadcastRouting("Markov: inactive (" + event.get("observations") +
                    " obs, need 20+)");
            }
            case ROUTING_KEYWORDS -> {
                webChatServer.broadcastRouting("Fast-route → " + event.get("agent") +
                    " (YAML routing_keywords match)");
            }
            case MAKER_THOUGHT -> {
                webChatServer.broadcastMaker("[Maker] " + event.get("action") + ": " + event.get("reason"));
            }
            case MAKER_GOAL -> {
                webChatServer.broadcastMaker("[Maker] New goal: \"" + event.get("goal") + "\"");
            }
            case MAKER_COMPLETE -> {
                webChatServer.broadcastMaker("[Maker] ✓ Goal completed: \"" + event.get("goal") + "\"");
            }
            case STREAM_START -> {
                webChatServer.broadcastStreamStart(event.get("agent"), event.get("model"));
            }
            case STREAM_CHUNK -> {
                webChatServer.broadcastStreamChunk(event.get("text"));
            }
            case STREAM_END -> {
                webChatServer.broadcastStreamEnd();
            }
            case DELEGATION -> {
                webChatServer.broadcastDelegation(event.get("agent"));
            }
            case SYSTEM -> {
                webChatServer.broadcastSystem(event.get("message"));
            }
            default -> {
                // Not handled by WebSocket sink
            }
        }
    }
}
