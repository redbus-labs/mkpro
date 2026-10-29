package com.mkpro.infra.mq;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MqTaskMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String taskId = UUID.randomUUID().toString();
    private String prompt;
    private String targetAgent = "Coordinator";
    private String sessionId;
    private String replyTo;
    private String correlationId;
    private long timestamp = System.currentTimeMillis();
    private Map<String, String> metadata = new HashMap<>();

    public MqTaskMessage() {}

    public MqTaskMessage(String prompt) {
        this.prompt = prompt;
    }

    public MqTaskMessage(String taskId, String prompt, String targetAgent) {
        if (taskId != null && !taskId.isBlank()) this.taskId = taskId;
        this.prompt = prompt;
        if (targetAgent != null && !targetAgent.isBlank()) this.targetAgent = targetAgent;
    }

    public String toJson() throws Exception {
        return MAPPER.writeValueAsString(this);
    }

    public static MqTaskMessage fromJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Empty task payload");
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) {
            try {
                MqTaskMessage msg = MAPPER.readValue(trimmed, MqTaskMessage.class);
                if (msg.getTaskId() == null || msg.getTaskId().isBlank()) {
                    msg.setTaskId(UUID.randomUUID().toString());
                }
                if (msg.getTargetAgent() == null || msg.getTargetAgent().isBlank()) {
                    msg.setTargetAgent("Coordinator");
                }
                return msg;
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid JSON task payload: " + e.getMessage(), e);
            }
        }
        return new MqTaskMessage(trimmed);
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getTargetAgent() { return targetAgent; }
    public void setTargetAgent(String targetAgent) { this.targetAgent = targetAgent; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getReplyTo() { return replyTo; }
    public void setReplyTo(String replyTo) { this.replyTo = replyTo; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
}
