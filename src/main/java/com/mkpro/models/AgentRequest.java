package com.mkpro.models;

import com.google.adk.tools.BaseTool;
import java.util.List;

public class AgentRequest {
    private String agentName;
    private String instruction;
    private String modelName;
    private Provider provider;
    private String userPrompt;
    private List<BaseTool> tools;

    public AgentRequest(String agentName, String instruction, String modelName, Provider provider, String userPrompt, List<BaseTool> tools) {
        this.agentName = agentName;
        this.instruction = instruction;
        this.modelName = modelName;
        this.provider = provider;
        this.userPrompt = userPrompt;
        this.tools = tools;
    }

    public String getAgentName() { return agentName; }
    public String getInstruction() { return instruction; }
    public String getModelName() { return modelName; }
    public Provider getProvider() { return provider; }
    public String getUserPrompt() { return userPrompt; }
    public List<BaseTool> getTools() { return tools; }
}
