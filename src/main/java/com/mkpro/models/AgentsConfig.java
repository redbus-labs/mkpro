package com.mkpro.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentsConfig {
    @JsonProperty("agents")
    private List<AgentDefinition> agents;

    public List<AgentDefinition> getAgents() { return agents; }
    public void setAgents(List<AgentDefinition> agents) { this.agents = agents; }
}
