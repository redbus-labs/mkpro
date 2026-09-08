package com.mkpro.agents;

import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.BaseTool;
import java.util.List;

public class CoderAgent extends LlmAgent {
    public CoderAgent(String name, String modelName, List<BaseTool> tools) {
        super(LlmAgent.builder()
            .name(name)
            .model(modelName)
            .tools(tools));
    }
}
