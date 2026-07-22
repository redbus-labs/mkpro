package com.mkpro.events;

import java.util.Collections;
import java.util.Map;

/**
 * A typed event emitted by mkpro components (routing, maker, streaming, etc).
 * Consumed by sinks (terminal, websocket, SSE, logging).
 */
public class MkProEvent {

    public enum Type {
        ROUTING_DECISION,    // Markov fast-route success
        ROUTING_BELOW,       // Markov below threshold, using Coordinator
        ROUTING_INACTIVE,    // Markov not enough observations
        ROUTING_KEYWORDS,    // YAML routing_keywords direct match
        MAKER_THOUGHT,       // Maker reasoning (CONTINUE/RETRY/ESCALATE/COMPLETE)
        MAKER_GOAL,          // New goal created
        MAKER_COMPLETE,      // Goal completed
        STREAM_START,        // Agent starts responding
        STREAM_CHUNK,        // Text chunk
        STREAM_END,          // Response complete
        DELEGATION,          // Coordinator delegated to agent
        SYSTEM,              // System messages
        GOAL_UPDATE,         // Goal status changed
        KNOWLEDGE_UPDATE,    // Scheduler refreshed a topic
        EDIT_PROPOSAL,       // File edit awaiting approval (carries EditProposal)
        EDIT_APPROVED,       // Edit was approved
        EDIT_REJECTED        // Edit was rejected
    }

    private final Type type;
    private final Map<String, String> data;
    private final long timestamp;
    private EditProposal editProposal; // Only set for EDIT_PROPOSAL events

    public MkProEvent(Type type, Map<String, String> data) {
        this.type = type;
        this.data = data != null ? data : Collections.emptyMap();
        this.timestamp = System.currentTimeMillis();
    }

    public Type getType() { return type; }
    public Map<String, String> getData() { return data; }
    public long getTimestamp() { return timestamp; }
    public EditProposal getEditProposal() { return editProposal; }

    public String get(String key) {
        return data.getOrDefault(key, "");
    }

    public String get(String key, String defaultValue) {
        return data.getOrDefault(key, defaultValue);
    }

    // Convenience factory methods
    public static MkProEvent routing(String agent, String confidence, String category) {
        return new MkProEvent(Type.ROUTING_DECISION, Map.of(
            "agent", agent, "confidence", confidence, "category", category));
    }

    public static MkProEvent routingBelow(String agent, String confidence) {
        return new MkProEvent(Type.ROUTING_BELOW, Map.of(
            "agent", agent, "confidence", confidence));
    }

    public static MkProEvent routingInactive(String observations) {
        return new MkProEvent(Type.ROUTING_INACTIVE, Map.of("observations", observations));
    }

    public static MkProEvent routingKeywords(String agent) {
        return new MkProEvent(Type.ROUTING_KEYWORDS, Map.of("agent", agent));
    }

    public static MkProEvent makerThought(String action, String reason) {
        return new MkProEvent(Type.MAKER_THOUGHT, Map.of(
            "action", action, "reason", reason));
    }

    public static MkProEvent makerGoal(String goal) {
        return new MkProEvent(Type.MAKER_GOAL, Map.of("goal", goal));
    }

    public static MkProEvent makerComplete(String goal) {
        return new MkProEvent(Type.MAKER_COMPLETE, Map.of("goal", goal));
    }

    public static MkProEvent system(String message) {
        return new MkProEvent(Type.SYSTEM, Map.of("message", message));
    }

    public static MkProEvent streamStart(String agent, String model) {
        return new MkProEvent(Type.STREAM_START, Map.of("agent", agent, "model", model != null ? model : ""));
    }

    public static MkProEvent streamChunk(String text) {
        return new MkProEvent(Type.STREAM_CHUNK, Map.of("text", text));
    }

    public static MkProEvent streamEnd() {
        return new MkProEvent(Type.STREAM_END, Map.of());
    }

    public static MkProEvent delegation(String agent) {
        return new MkProEvent(Type.DELEGATION, Map.of("agent", agent));
    }

    public static MkProEvent editProposal(EditProposal proposal) {
        MkProEvent event = new MkProEvent(Type.EDIT_PROPOSAL, Map.of(
            "id", proposal.getId(), "path", proposal.getFilePath()));
        event.editProposal = proposal;
        return event;
    }

    public static MkProEvent editApproved(String proposalId, String path) {
        return new MkProEvent(Type.EDIT_APPROVED, Map.of("id", proposalId, "path", path));
    }

    public static MkProEvent editRejected(String proposalId, String path) {
        return new MkProEvent(Type.EDIT_REJECTED, Map.of("id", proposalId, "path", path));
    }
}
