package com.mkpro.models;

public class AgentConfig {
    private Provider provider;
    private String modelName;

    public AgentConfig(Provider provider, String modelName) {
        this.provider = provider;
        this.modelName = modelName;
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
}
