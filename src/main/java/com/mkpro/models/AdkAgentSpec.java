package com.mkpro.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AdkAgentSpec {

    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("instruction")
    private String instruction;

    @JsonProperty("model")
    private String model;

    @JsonProperty("adapter")
    private String adapter;

    @JsonProperty("provider")
    private String provider = "GEMINI";

    @JsonProperty("fallback_model")
    private String fallbackModel;

    @JsonProperty("needs_context")
    private boolean needsContext = true;

    @JsonProperty("tools")
    private List<Object> tools = new ArrayList<>();

    @JsonProperty("skills")
    private List<String> skills = new ArrayList<>();

    @JsonProperty("sub_agents")
    private Map<String, AdkAgentSpec> subAgents = new LinkedHashMap<>();

    @JsonProperty("generate_content_config")
    private AdkSamplingConfig generateContentConfig;

    public AdkAgentSpec() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getInstruction() {
        return instruction;
    }

    public void setInstruction(String instruction) {
        this.instruction = instruction;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getAdapter() {
        return adapter;
    }

    public void setAdapter(String adapter) {
        this.adapter = adapter;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getFallbackModel() {
        return fallbackModel;
    }

    public void setFallbackModel(String fallbackModel) {
        this.fallbackModel = fallbackModel;
    }

    public boolean isNeedsContext() {
        return needsContext;
    }

    public void setNeedsContext(boolean needsContext) {
        this.needsContext = needsContext;
    }

    public List<Object> getTools() {
        return tools;
    }

    public void setTools(List<Object> tools) {
        this.tools = tools;
    }

    public List<String> getSkills() {
        return skills;
    }

    public void setSkills(List<String> skills) {
        this.skills = skills;
    }

    public Map<String, AdkAgentSpec> getSubAgents() {
        return subAgents;
    }

    public void setSubAgents(Map<String, AdkAgentSpec> subAgents) {
        this.subAgents = subAgents;
    }

    public AdkSamplingConfig getGenerateContentConfig() {
        return generateContentConfig;
    }

    public void setGenerateContentConfig(AdkSamplingConfig generateContentConfig) {
        this.generateContentConfig = generateContentConfig;
    }

    public AgentDefinition toAgentDefinition() {
        AgentDefinition def = new AgentDefinition();
        def.setName(this.name);
        def.setDescription(this.description);
        def.setInstruction(this.instruction);
        def.setModel(this.model);
        def.setProvider(this.provider != null ? this.provider : "GEMINI");
        def.setFallbackModel(this.fallbackModel);
        def.setNeedsContext(this.needsContext);

        if (this.tools != null) {
            List<String> toolNames = new ArrayList<>();
            for (Object t : this.tools) {
                if (t instanceof String s) {
                    toolNames.add(s);
                } else if (t instanceof Map<?, ?> m) {
                    for (Object key : m.keySet()) {
                        if (key != null) {
                            toolNames.add(key.toString());
                        }
                    }
                }
            }
            def.setTools(toolNames);
        }
        return def;
    }
}
