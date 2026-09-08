package com.mkpro.agents;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.BaseLlm;
import com.google.adk.tools.BaseTool;
import com.mkpro.models.AgentDefinition;

import java.util.List;

/**
 * AgentFactory builds LlmAgent instances from AgentDefinition + resolved tools.
 * Single responsibility: takes a definition and produces a configured agent.
 */
public class AgentFactory {

    private static final String BASE_AGENT_POLICY =
        "Authority:\n" +
        "- You are an autonomous specialist operating under the Coordinator agent.\n" +
        "- You MUST act only within the scope of your assigned responsibilities.\n" +
        "\n" +
        "General Rules:\n" +
        "- You MUST follow all explicit instructions provided by the Coordinator.\n" +
        "- You MUST analyze the task and relevant context before taking any action.\n" +
        "- You MUST produce deterministic, reproducible outputs.\n" +
        "- You SHOULD minimize unnecessary actions and side effects.\n" +
        "- You MUST clearly report what actions were taken and why.\n" +
        "- You MUST NOT assume missing information; request clarification when required.\n" +
        "\n" +
        "Tool Usage Policy:\n" +
        "- You MUST use only the tools explicitly available to you.\n" +
        "- You MUST NOT simulate or claim tool execution that did not occur.\n" +
        "- You SHOULD prefer read-only operations unless modification is explicitly required.\n" +
        "\n" +
        "Safety & Quality:\n" +
        "- You MUST preserve data integrity and avoid destructive actions.\n" +
        "- You SHOULD favor minimal, reversible changes.\n" +
        "- You MUST report errors, risks, or inconsistencies immediately.\n";

    /**
     * Build an LlmAgent for a sub-agent (not the Coordinator).
     *
     * @param def            The agent definition from YAML
     * @param llm            The resolved LLM for this agent
     * @param tools          The resolved tools for this agent
     * @param projectContext The project context string to inject
     * @return A fully configured LlmAgent
     */
    public LlmAgent createSubAgent(AgentDefinition def, BaseLlm llm, List<BaseTool> tools, String projectContext) {
        String instruction = buildSubAgentInstruction(def, projectContext);

        LlmAgent.Builder builder = LlmAgent.builder()
                .name(def.getName())
                .description(def.getDescription())
                .instruction(instruction)
                .model(llm)
                .planning(true);

        if (tools != null && !tools.isEmpty()) {
            builder.tools(tools);
        }

        return builder.build();
    }

    /**
     * Build the Coordinator agent.
     *
     * @param def            The coordinator's AgentDefinition
     * @param llm            The resolved LLM
     * @param tools          Coordinator tools (delegation tools + fetch_url)
     * @param projectContext The project context string
     * @return The Coordinator LlmAgent
     */
    public LlmAgent createCoordinator(AgentDefinition def, BaseLlm llm, List<BaseTool> tools, String projectContext) {
        String instruction;
        if (def != null && def.getInstruction() != null) {
            instruction = def.getInstruction() + "\n\n" + projectContext;
        } else {
            instruction = projectContext;
        }

        return LlmAgent.builder()
                .name("Coordinator")
                .description("The main orchestrator agent.")
                .instruction(instruction)
                .model(llm)
                .tools(tools)
                .planning(true)
                .build();
    }

    /**
     * Get the base agent policy (for use in delegation tool instructions).
     */
    public String getBaseAgentPolicy() {
        return BASE_AGENT_POLICY;
    }

    private String buildSubAgentInstruction(AgentDefinition def, String projectContext) {
        StringBuilder sb = new StringBuilder();
        if (projectContext != null && !projectContext.isEmpty()) {
            sb.append(projectContext).append("\n\n");
        }
        sb.append("Specific Instruction: ");
        if (def.getInstruction() != null) {
            sb.append(def.getInstruction());
        }
        return sb.toString();
    }
}
