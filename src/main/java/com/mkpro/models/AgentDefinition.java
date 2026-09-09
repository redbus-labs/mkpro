package com.mkpro.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class AgentDefinition {
    private String name;
    private String description;
    private String instruction;
    private String provider;
    private String model;

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
}
