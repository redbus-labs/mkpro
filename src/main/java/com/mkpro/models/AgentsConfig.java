package com.mkpro.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class AgentsConfig {
    @JsonProperty("agents")
    private List<AgentDefinition> agents;

    public List<AgentDefinition> getAgents() { return agents; }
    public void setAgents(List<AgentDefinition> agents) { this.agents = agents; }
}
