package com.mkpro.commands.impl;

import com.google.adk.runner.Runner;
import com.mkpro.CentralMemory;
import com.mkpro.agents.AgentManager;
import com.mkpro.commands.CommandRegistry;
import com.mkpro.core.MkProContext;
import com.mkpro.core.StoreKeys;
import com.mkpro.models.AgentConfig;
import com.mkpro.models.Provider;
import com.mkpro.plugins.FilterConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CompactCommand}.
 * Uses a basic state-check approach for testing instead of Mockito, 
 * to avoid environment issues with AgentManager constructor dependencies.
 */
class CompactCommandTest {

    private CompactCommand command;
    private MkProContext context;
    private Map<String, AgentConfig> agentConfigs;

    @BeforeEach
    void setUp() {
        command = new CompactCommand();
        context = new MkProContext();
        agentConfigs = new HashMap<>();
        agentConfigs.put("Coordinator", new AgentConfig(Provider.OLLAMA, "test-model"));
        context.setAgentConfigs(agentConfigs);
    }

    @Test
    @DisplayName("Verify command metadata: name and description")
    void testCommandMetadata() {
        assertEquals("compact", command.getName());
        assertNotNull(command.getDescription());
        assertFalse(command.getDescription().isBlank());

        CommandRegistry registry = new CommandRegistry();
        registry.register(command);
        assertSame(command, registry.getCommands().get("compact"));
        assertSame(command, registry.getCommands().get("/compact"));
    }

    @Test
    @DisplayName("Execute without args updates turns context if already set")
    void testExecuteWithoutArgs() throws Exception {
        context.setMaxTurns(5);
        command.execute(new String[0], context);
        assertEquals(5, context.getMaxTurns());
    }

    @Test
    @DisplayName("Execute with numeric argument updates maxTurns")
    void testExecuteWithSpecificTurnLimit() throws Exception {
        context.setMaxTurns(-1);
        command.execute(new String[]{"10"}, context);
        assertEquals(10, context.getMaxTurns());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    @DisplayName("Execute with 0 or -1 disables compaction limit")
    void testExecuteDisablingCompactionLimit(String arg) throws Exception {
        context.setMaxTurns(10);
        command.execute(new String[]{arg}, context);
        assertEquals(-1, context.getMaxTurns());
    }

    @Test
    @DisplayName("Execute with invalid non-numeric argument falls back gracefully")
    void testExecuteWithInvalidNonNumericArg() throws Exception {
        context.setMaxTurns(4);
        assertDoesNotThrow(() -> command.execute(new String[]{"invalid_num"}, context));
        assertEquals(4, context.getMaxTurns());
    }

    @Test
    @DisplayName("Execution through CommandRegistry updates context")
    void testExecutionThroughCommandRegistry() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command);

        registry.executeCommand("/compact 15", context);
        assertEquals(15, context.getMaxTurns());

        registry.executeCommand("compact 25", context);
        assertEquals(25, context.getMaxTurns());

        registry.executeCommand("/compact 0", context);
        assertEquals(-1, context.getMaxTurns());
    }

    @Test
    @DisplayName("Verify FilterConfig persistence in CentralMemory across subcommands")
    void testFilterConfigPersistenceInCentralMemory() throws Exception {
        // Test turn limits persistence
        command.execute(new String[]{"12"}, context);
        FilterConfig saved = CentralMemory.getInstance().get(StoreKeys.FILTER_CONFIG, FilterConfig.class);
        assertNotNull(saved);
        assertEquals(12, saved.getMaxTurns());

        // Test prune settings persistence
        command.execute(new String[]{"prune", "3500"}, context);
        saved = CentralMemory.getInstance().get(StoreKeys.FILTER_CONFIG, FilterConfig.class);
        assertNotNull(saved);
        assertTrue(saved.isPruneToolOutputs());
        assertEquals(3500, saved.getMaxToolPayloadChars());

        // Test prune off persistence
        command.execute(new String[]{"prune", "off"}, context);
        saved = CentralMemory.getInstance().get(StoreKeys.FILTER_CONFIG, FilterConfig.class);
        assertNotNull(saved);
        assertFalse(saved.isPruneToolOutputs());

        // Test churn on/off persistence
        command.execute(new String[]{"churn", "on"}, context);
        saved = CentralMemory.getInstance().get(StoreKeys.FILTER_CONFIG, FilterConfig.class);
        assertNotNull(saved);
        assertTrue(saved.isEvictStaleToolChurn());

        command.execute(new String[]{"churn", "off"}, context);
        saved = CentralMemory.getInstance().get(StoreKeys.FILTER_CONFIG, FilterConfig.class);
        assertNotNull(saved);
        assertFalse(saved.isEvictStaleToolChurn());

        // Test default execute persistence
        saved.setMaxTurns(20);
        context.setFilterConfig(saved);
        command.execute(new String[0], context);
        saved = CentralMemory.getInstance().get(StoreKeys.FILTER_CONFIG, FilterConfig.class);
        assertNotNull(saved);
        assertEquals(20, saved.getMaxTurns());
    }
}
