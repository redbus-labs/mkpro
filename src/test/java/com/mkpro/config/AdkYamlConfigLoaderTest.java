package com.mkpro.config;

import com.mkpro.models.AdkAgentSpec;
import com.mkpro.models.AdkSamplingConfig;
import com.mkpro.models.AgentDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AdkYamlConfigLoaderTest {

    private AdkYamlConfigLoader loader;
    private Path polyglotDir;

    @BeforeEach
    void setUp() {
        loader = new AdkYamlConfigLoader();
        polyglotDir = Paths.get("src/main/resources/teams/polyglot");
        if (!Files.exists(polyglotDir)) {
            polyglotDir = Paths.get("submission");
        }
    }

    @Test
    @DisplayName("Test loading agent.yaml with !include directives for markdown prompts and sampling configs")
    void testLoadCoordinatorAgentYamlWithIncludes() throws IOException {
        Path agentYaml = polyglotDir.resolve("agent.yaml");
        assertTrue(Files.exists(agentYaml), "agent.yaml must exist at " + agentYaml);

        AdkAgentSpec spec = loader.load(agentYaml);
        assertNotNull(spec, "Loaded AdkAgentSpec must not be null");
        assertEquals("coordinator", spec.getName());
        assertEquals("gemma-4-31b-it-qat-w4a16-ct", spec.getModel());
        assertEquals("main_lora", spec.getAdapter());
        assertNotNull(spec.getDescription());

        // Verify !include prompts/system.md
        assertNotNull(spec.getInstruction(), "Instruction from system.md should be resolved");
        assertTrue(spec.getInstruction().contains("Authority:") || spec.getInstruction().contains("Coordinator")
                || spec.getInstruction().length() > 50, "Instruction must contain the resolved markdown content");

        // Verify !include configs/sampling.yaml
        AdkSamplingConfig sampling = spec.getGenerateContentConfig();
        assertNotNull(sampling, "Sampling configuration should be loaded from configs/sampling.yaml");
        assertEquals(0.2, sampling.getTemperature(), 0.001);
        assertEquals(0.95, sampling.getTopP(), 0.001);
        assertEquals(16384, sampling.getMaxOutputTokens());

        assertNotNull(sampling.getThinkingConfig(), "ThinkingConfig should be populated");
        assertEquals(4096, sampling.getThinkingConfig().getThinkingBudget());
        assertTrue(sampling.getThinkingConfig().getIncludeThoughts());

        // Verify tools
        List<Object> tools = spec.getTools();
        assertNotNull(tools);
        assertTrue(tools.contains("run_command"));
        assertTrue(tools.contains("read_file"));
        assertTrue(tools.contains("edit_file"));
        assertTrue(tools.contains("write_file"));
        assertTrue(tools.contains("get_status"));
        assertTrue(tools.contains("submit_patch"));
    }

    @Test
    @DisplayName("Test loading nested sub-agent YAMLs with relative !include prompts and sampling configs")
    void testLoadSubAgentsWithIncludesAndSampling() throws IOException {
        Path coderYaml = polyglotDir.resolve("sub_agents").resolve("coder.yaml");
        assertTrue(Files.exists(coderYaml), "coder.yaml must exist at " + coderYaml);

        AdkAgentSpec coderSpec = loader.load(coderYaml);
        assertNotNull(coderSpec);
        assertEquals("coder_agent", coderSpec.getName());
        assertEquals("gemma-4-31b-it-qat-w4a16-ct", coderSpec.getModel());
        assertEquals("main_lora", coderSpec.getAdapter());

        // Verify resolved instruction from ../prompts/coder.md
        assertNotNull(coderSpec.getInstruction());
        assertTrue(coderSpec.getInstruction().contains("Role: Software Engineer")
                || coderSpec.getInstruction().contains("Coder")
                || coderSpec.getInstruction().length() > 50);

        // Verify sampling config from ../configs/sampling.yaml
        AdkSamplingConfig coderSampling = coderSpec.getGenerateContentConfig();
        assertNotNull(coderSampling);
        assertEquals(0.2, coderSampling.getTemperature(), 0.001);
        assertEquals(0.95, coderSampling.getTopP(), 0.001);

        // Test architect sub-agent
        Path architectYaml = polyglotDir.resolve("sub_agents").resolve("architect.yaml");
        assertTrue(Files.exists(architectYaml));
        AdkAgentSpec architectSpec = loader.load(architectYaml);
        assertEquals("architect_agent", architectSpec.getName());
        assertNotNull(architectSpec.getInstruction());

        // Test code_analyzer sub-agent with tool_lora adapter
        Path analyzerYaml = polyglotDir.resolve("sub_agents").resolve("code_analyzer.yaml");
        assertTrue(Files.exists(analyzerYaml));
        AdkAgentSpec analyzerSpec = loader.load(analyzerYaml);
        assertEquals("code_analyzer_agent", analyzerSpec.getName());
        assertEquals("tool_lora", analyzerSpec.getAdapter());
    }

    @Test
    @DisplayName("Test loading modular team directory and discovering skills, sub-agents, and capabilities")
    void testLoadModularTeamWithSkillsAndSubAgents() throws IOException {
        AdkAgentSpec teamSpec = loader.loadModularTeam(polyglotDir);
        assertNotNull(teamSpec);
        assertEquals("coordinator", teamSpec.getName());

        // Verify sub-agents map
        Map<String, AdkAgentSpec> subAgents = teamSpec.getSubAgents();
        assertNotNull(subAgents);
        assertFalse(subAgents.isEmpty(), "Sub-agents should be populated");
        assertTrue(subAgents.containsKey("coder_agent") || subAgents.containsKey("code_analyzer_agent"));

        // Verify discovered skills
        List<String> skills = teamSpec.getSkills();
        assertNotNull(skills);
        assertTrue(skills.contains("codebase_search"), "Should discover codebase_search skill");
        assertTrue(skills.contains("file_operations"), "Should discover file_operations skill");
        assertTrue(skills.contains("git_operations"), "Should discover git_operations skill");
        assertTrue(skills.contains("memory_management"), "Should discover memory_management skill");
        assertTrue(skills.contains("repo_navigation"), "Should discover repo_navigation skill");
        assertTrue(skills.contains("shell_execution"), "Should discover shell_execution skill");
        assertTrue(skills.contains("ssh_remote_ops"), "Should discover ssh_remote_ops skill");
    }

    @Test
    @DisplayName("Test converting AdkAgentSpec to AgentDefinition for AgentManager")
    void testToAgentDefinitionConversion() throws IOException {
        Path coderYaml = polyglotDir.resolve("sub_agents").resolve("coder.yaml");
        AdkAgentSpec coderSpec = loader.load(coderYaml);

        AgentDefinition def = coderSpec.toAgentDefinition();
        assertNotNull(def);
        assertEquals("coder_agent", def.getName());
        assertEquals(coderSpec.getDescription(), def.getDescription());
        assertEquals(coderSpec.getInstruction(), def.getInstruction());
        assertEquals(coderSpec.getModel(), def.getModel());
        assertNotNull(def.getProvider());
        assertNotNull(def.getTools());
        assertTrue(def.getTools().contains("run_command"));
        assertTrue(def.getTools().contains("read_file"));

        // Test loader helper
        AgentDefinition defViaLoader = loader.toAgentDefinition(coderSpec);
        assertNotNull(defViaLoader);
        assertEquals("coder_agent", defViaLoader.getName());
    }

    @Test
    @DisplayName("Test seamless loading of AgentDefinitions from modular team directory for AgentManager")
    void testLoadAgentDefinitionsForAgentManager() {
        Map<String, AgentDefinition> defs = loader.loadAgentDefinitions(polyglotDir);
        assertNotNull(defs);
        assertFalse(defs.isEmpty(), "Definitions should not be empty");
        assertTrue(defs.containsKey("coordinator"), "Should contain coordinator definition");
        assertTrue(defs.containsKey("coder_agent"), "Should contain coder_agent definition");

        AgentDefinition coordinatorDef = defs.get("coordinator");
        assertNotNull(coordinatorDef.getInstruction());
        assertNotNull(coordinatorDef.getModel());
        assertFalse(coordinatorDef.getTools().isEmpty());
    }

    @Test
    @DisplayName("Test backward compatibility with legacy team YAML files")
    void testLegacyYamlCompatibility() {
        Path legacyYaml = Paths.get("src/main/resources/teams/polyglot.yaml");
        if (Files.exists(legacyYaml)) {
            Map<String, AgentDefinition> defs = loader.loadAgentDefinitions(legacyYaml);
            assertNotNull(defs);
            assertFalse(defs.isEmpty(), "Should load definitions from legacy YAML");
            assertTrue(defs.containsKey("Coordinator") || defs.containsKey("GitAgent"));
        }
    }
}
