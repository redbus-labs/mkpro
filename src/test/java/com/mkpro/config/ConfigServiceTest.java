package com.mkpro.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigServiceTest {

    @Test
    @DisplayName("Test setupTeamsDir initializes and returns valid teams directory")
    void testSetupTeamsDir() throws IOException {
        Path teamsDir = ConfigService.setupTeamsDir();
        assertNotNull(teamsDir);
        assertTrue(Files.exists(teamsDir), "Teams directory must exist");
        assertTrue(Files.isDirectory(teamsDir), "Teams path must be a directory");

        // Verify modular polyglot folder exists
        Path polyglotDir = teamsDir.resolve("polyglot");
        assertTrue(Files.exists(polyglotDir), "polyglot directory must exist in teams/");
        assertTrue(Files.exists(polyglotDir.resolve("agent.yaml")), "agent.yaml must exist in teams/polyglot/");
        assertTrue(Files.exists(polyglotDir.resolve("sub_agents")), "sub_agents/ must exist in teams/polyglot/");
        assertTrue(Files.exists(polyglotDir.resolve("prompts")), "prompts/ must exist in teams/polyglot/");
        assertTrue(Files.exists(polyglotDir.resolve("skills")), "skills/ must exist in teams/polyglot/");
    }

    @Test
    @DisplayName("Test exportTeamsResources exports all files and modular folders to target directory")
    void testExportTeamsResources(@TempDir Path tempDir) throws IOException {
        Path targetTeams = tempDir.resolve("test_teams");
        assertFalse(Files.exists(targetTeams));

        ConfigService.exportTeamsResources(targetTeams);
        assertTrue(Files.exists(targetTeams));

        // Legacy .yaml files
        assertTrue(Files.exists(targetTeams.resolve("polyglot.yaml")), "polyglot.yaml should be exported");
        assertTrue(Files.exists(targetTeams.resolve("adk_updater.yaml")), "adk_updater.yaml should be exported");
        assertTrue(Files.exists(targetTeams.resolve("minimal.yaml")), "minimal.yaml should be exported");

        // Modular polyglot structure
        Path polyglot = targetTeams.resolve("polyglot");
        assertTrue(Files.exists(polyglot), "polyglot/ directory should be exported");
        assertTrue(Files.exists(polyglot.resolve("agent.yaml")), "agent.yaml should be exported");
        assertTrue(Files.exists(polyglot.resolve("eval_config.yaml")), "eval_config.yaml should be exported");
        assertTrue(Files.exists(polyglot.resolve("configs").resolve("sampling.yaml")), "configs/sampling.yaml should be exported");
        assertTrue(Files.exists(polyglot.resolve("prompts").resolve("coder.md")), "prompts/coder.md should be exported");
        assertTrue(Files.exists(polyglot.resolve("sub_agents").resolve("coder.yaml")), "sub_agents/coder.yaml should be exported");
        assertTrue(Files.exists(polyglot.resolve("skills").resolve("file_operations").resolve("SKILL.md")), "skills/file_operations/SKILL.md should be exported");
    }

    @Test
    @DisplayName("Test exportTeamsResources is idempotent and extracts missing files into pre-existing directory")
    void testExportTeamsResourcesIdempotentAndMissingExtraction(@TempDir Path tempDir) throws IOException {
        Path targetTeams = tempDir.resolve("existing_teams");
        Files.createDirectories(targetTeams);

        // Pre-create an existing file
        Path customFile = targetTeams.resolve("custom_team.yaml");
        Files.writeString(customFile, "name: custom");

        // First export
        ConfigService.exportTeamsResources(targetTeams);
        assertTrue(Files.exists(customFile), "Custom file should remain intact");
        assertEquals("name: custom", Files.readString(customFile));
        assertTrue(Files.exists(targetTeams.resolve("polyglot").resolve("agent.yaml")));

        // Delete a file inside polyglot to simulate missing resource
        Path coderPrompt = targetTeams.resolve("polyglot").resolve("prompts").resolve("coder.md");
        Files.deleteIfExists(coderPrompt);
        assertFalse(Files.exists(coderPrompt));

        // Second export should re-extract missing coderPrompt without modifying existing files
        ConfigService.exportTeamsResources(targetTeams);
        assertTrue(Files.exists(coderPrompt), "Missing prompt file should be re-extracted");
        assertTrue(Files.exists(customFile), "Custom file should still exist");
    }
}