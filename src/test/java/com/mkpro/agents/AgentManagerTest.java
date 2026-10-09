package com.mkpro.agents;

import com.mkpro.ActionLogger;
import com.mkpro.models.AgentDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentManagerTest {

    private AgentManager agentManager;
    private Path polyglotDir;
    private Path legacyYaml;

    @BeforeEach
    void setUp() {
        polyglotDir = Paths.get("src/main/resources/teams/polyglot");
        legacyYaml = Paths.get("src/main/resources/teams/polyglot.yaml");

        // Construct AgentManager with null services for configuration testing
        agentManager = new AgentManager(
                null,
                null,
                null,
                "test-api-key",
                "http://localhost:11434",
                null,
                null,
                null,
                polyglotDir,
                null,
                null
        );
    }

    @Test
    @DisplayName("Test loadAgentDefinitions from modular ADK directory via AdkYamlConfigLoader")
    void testLoadAgentDefinitionsFromModularDirectory() {
        if (Files.exists(polyglotDir)) {
            Map<String, AgentDefinition> defs = agentManager.loadAgentDefinitions(polyglotDir);
            assertNotNull(defs);
            assertFalse(defs.isEmpty(), "Loaded definitions should not be empty");
            assertTrue(defs.containsKey("coordinator"), "Should contain coordinator agent");
            assertTrue(defs.containsKey("coder_agent"), "Should contain coder_agent");

            AgentDefinition coordinatorDef = defs.get("coordinator");
            assertNotNull(coordinatorDef.getInstruction());
            assertTrue(coordinatorDef.getInstruction().contains("Authority:") || coordinatorDef.getInstruction().contains("Coordinator") || coordinatorDef.getInstruction().length() > 50);
        }
    }

    @Test
    @DisplayName("Test loadAgentDefinitions fallback to legacy YAML file")
    void testLoadAgentDefinitionsFromLegacyYaml() {
        if (Files.exists(legacyYaml)) {
            Map<String, AgentDefinition> defs = agentManager.loadAgentDefinitions(legacyYaml);
            assertNotNull(defs);
            assertFalse(defs.isEmpty(), "Should load definitions from legacy YAML");
            assertTrue(defs.containsKey("Coordinator") || defs.containsKey("GitAgent") || defs.containsKey("coordinator"));
        }
    }

    @Test
    @DisplayName("Test loadTeam resolves modular ADK directory")
    void testLoadTeamModularResolution() {
        if (Files.exists(polyglotDir)) {
            agentManager.loadTeam("polyglot");
            Map<String, AgentDefinition> defs = agentManager.getAgentDefinitions();
            assertNotNull(defs);
            assertFalse(defs.isEmpty());
            assertTrue(defs.containsKey("coordinator") || defs.containsKey("coder_agent"));
        }
    }

    @Test
    @DisplayName("Test loadTeam fallback to legacy team YAML")
    void testLoadTeamLegacyResolution() {
        Path defaultYaml = Paths.get("src/main/resources/teams/default.yaml");
        if (Files.exists(defaultYaml)) {
            agentManager.loadTeam("default");
            Map<String, AgentDefinition> defs = agentManager.getAgentDefinitions();
            assertNotNull(defs);
            assertFalse(defs.isEmpty());
        }
    }

    @Test
    @DisplayName("Test loadAgentDefinitions graceful fallback when path is non-existent")
    void testLoadAgentDefinitionsNonExistentPath() {
        Path nonExistent = Paths.get("non/existent/path/agent.yaml");
        Map<String, AgentDefinition> defs = agentManager.loadAgentDefinitions(nonExistent);
        assertNotNull(defs);
        assertTrue(defs.isEmpty());
    }
}
