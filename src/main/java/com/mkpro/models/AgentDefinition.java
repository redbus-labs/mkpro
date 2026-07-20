package com.mkpro.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.Serializable;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentDefinition implements Serializable {
    private static final long serialVersionUID = 1L;

    private String name;
    private String description;
    private String instruction;
    private String provider;
    private String model;
    private java.util.List<String> tools;
    private String fallbackModel;  // e.g., "codestral@gpu4090" — tried if primary fails
    private int maxRetries = 1;    // How many times to retry with fallback (default: 1)
    private boolean needsContext = true;  // Whether to inject project context into this agent (default: true)
    private java.util.List<String> routingKeywords;  // Keywords for IntentClassifier fast-routing (optional)

    public AgentDefinition() {}

    public AgentDefinition(String name, String description, String instruction) {
        this.name = name;
        this.description = description;
        this.instruction = instruction;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getInstruction() { return instruction; }
    public void setInstruction(String instruction) { this.instruction = instruction; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public java.util.List<String> getTools() { return tools; }
    public void setTools(java.util.List<String> tools) { this.tools = tools; }

    @com.fasterxml.jackson.annotation.JsonProperty("fallback_model")
    public String getFallbackModel() { return fallbackModel; }
    public void setFallbackModel(String fallbackModel) { this.fallbackModel = fallbackModel; }

    @com.fasterxml.jackson.annotation.JsonProperty("max_retries")
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    @com.fasterxml.jackson.annotation.JsonProperty("needs_context")
    public boolean isNeedsContext() { return needsContext; }
    public void setNeedsContext(boolean needsContext) { this.needsContext = needsContext; }

    @com.fasterxml.jackson.annotation.JsonProperty("routing_keywords")
    public java.util.List<String> getRoutingKeywords() { return routingKeywords; }
    public void setRoutingKeywords(java.util.List<String> routingKeywords) { this.routingKeywords = routingKeywords; }
}
