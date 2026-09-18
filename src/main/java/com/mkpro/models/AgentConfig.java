package com.mkpro.models;

import java.io.Serializable;

public class AgentConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    private Provider provider;
    private String modelName;
    private String serverUrl; // Optional: per-agent Ollama endpoint override (null = use default)

    public AgentConfig(Provider provider, String modelName) {
        this.provider = provider;
        this.modelName = modelName;
        this.serverUrl = null;
    }

    public AgentConfig(Provider provider, String modelName, String serverUrl) {
        this.provider = provider;
        this.modelName = modelName;
        this.serverUrl = serverUrl;
    }

    public Provider getProvider() {
        return provider;
    }

    public void setProvider(Provider provider) {
        this.provider = provider;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    /**
     * Returns true if this config has a per-agent server URL override.
     */
    public boolean hasServerUrl() {
        return serverUrl != null && !serverUrl.isEmpty();
    }
}
