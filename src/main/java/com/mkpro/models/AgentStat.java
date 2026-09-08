package com.mkpro.models;

import java.io.Serializable;
import java.time.Instant;

public class AgentStat implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Instant timestamp;
    private String agentName;
    private String provider;
    private String model;
    private long durationMs;
    private boolean success;
    private int inputLength;
    private int outputLength;
    private long inputTokens;
    private long outputTokens;
    private long totalTokens;
    private String sessionId;

    public AgentStat(String agentName, String provider, String model, long durationMs, boolean success, 
                     int inputLength, int outputLength, long inputTokens, long outputTokens, 
                     long totalTokens, String sessionId) {
        this.timestamp = Instant.now();
        this.agentName = agentName;
        this.provider = provider;
        this.model = model;
        this.durationMs = durationMs;
        this.success = success;
        this.inputLength = inputLength;
        this.outputLength = outputLength;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
        this.sessionId = sessionId;
    }

    public Instant getTimestamp() { return timestamp; }
    public String getAgentName() { return agentName; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public long getDurationMs() { return durationMs; }
    public boolean isSuccess() { return success; }
    public int getInputLength() { return inputLength; }
    public int getOutputLength() { return outputLength; }
    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public long getTotalTokens() { return totalTokens; }
    public String getSessionId() { return sessionId; }
    
    @Override
    public String toString() {
        return String.format("[%s] %s (%s/%s) - %dms - %s - Tokens: %d", 
                timestamp, agentName, provider, model, durationMs, success ? "OK" : "FAIL", totalTokens);
    }
}