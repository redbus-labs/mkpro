package com.mkpro;

import com.mkpro.models.AgentConfig;
import com.mkpro.models.AgentStat;
import com.mkpro.models.Goal;
import com.mkpro.models.McpServer;
import com.mkpro.models.Provider;
import org.junit.jupiter.api.*;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CentralMemory's hot/shared split architecture.
 * Verifies:
 * - Hot path (stats) is always available without shared DB contention
 * - Shared path (configs, goals, servers) uses brief locks with caching
 * - Multi-instance safety: two CentralMemory instances sharing same DB don't corrupt data
 * - Cache behavior: reads served from cache, cache invalidated on writes
 */
public class CentralMemoryTest {

    private Path tempDir;
    private CentralMemory memory;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = java.nio.file.Files.createTempDirectory("cmtest-");
        Path sharedDb = tempDir.resolve("shared").resolve("central_memory.db");
        Path localDb = tempDir.resolve("local").resolve("stats.db");
        memory = new CentralMemory(sharedDb, localDb);
    }

    @AfterEach
    void tearDown() {
        if (memory != null) {
            memory.close();
            memory = null;
        }
        // Best-effort cleanup; Windows may hold WAL files — that's OK for tests
        try {
            java.nio.file.Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }

    // ==========================================================================
    // HOT PATH TESTS — Agent Stats
    // ==========================================================================

    @Test
    void saveAndRetrieveAgentStat() {
        AgentStat stat = createStat("Coder", true, 1500);
        memory.saveAgentStat(stat);

        List<AgentStat> stats = memory.getAgentStats();
        assertEquals(1, stats.size());
        assertEquals("Coder", stats.get(0).getAgentName());
    }

    @Test
    void multipleStatsAccumulate() {
        memory.saveAgentStat(createStat("Coder", true, 1000));
        memory.saveAgentStat(createStat("Tester", true, 2000));
        memory.saveAgentStat(createStat("Coder", false, 500));

        List<AgentStat> stats = memory.getAgentStats();
        assertEquals(3, stats.size());
    }

    @Test
    void statsHighFrequencyWrites() throws Exception {
        // Simulate the hot path: many stats written in rapid succession
        int count = 100;
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(count);

        for (int i = 0; i < count; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    memory.saveAgentStat(createStat("Agent-" + idx, true, idx * 10));
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "All writes should complete within 10s");
        executor.shutdown();

        List<AgentStat> stats = memory.getAgentStats();
        assertEquals(count, stats.size());
    }

    // ==========================================================================
    // CACHE BEHAVIOR TESTS — Agent Configs
    // ==========================================================================

    @Test
    void saveAndRetrieveAgentConfig() {
        AgentConfig config = new AgentConfig(Provider.GEMINI, "gemini-2.0-flash");
        memory.saveAgentConfig("Coder", config);

        AgentConfig retrieved = memory.getAgentConfigs("Coder");
        assertNotNull(retrieved);
        assertEquals(Provider.GEMINI, retrieved.getProvider());
        assertEquals("gemini-2.0-flash", retrieved.getModelName());
    }

    @Test
    void getAllAgentConfigsReturnsCache() {
        memory.saveAgentConfig("Coder", new AgentConfig(Provider.GEMINI, "gemini-2.0-flash"));
        memory.saveAgentConfig("Tester", new AgentConfig(Provider.OLLAMA, "llama3"));

        Map<String, AgentConfig> all = memory.getAllAgentConfigs();
        assertEquals(2, all.size());
        assertTrue(all.containsKey("Coder"));
        assertTrue(all.containsKey("Tester"));
    }

    @Test
    void deleteAgentConfigRemovesFromCache() {
        memory.saveAgentConfig("Coder", new AgentConfig(Provider.GEMINI, "gemini-2.0-flash"));
        assertNotNull(memory.getAgentConfigs("Coder"));

        memory.deleteAgentConfig("Coder");
        assertNull(memory.getAgentConfigs("Coder"));
    }

    @Test
    void configCacheServedWithoutDbAccess() {
        // Save a config
        memory.saveAgentConfig("Architect", new AgentConfig(Provider.BEDROCK, "claude-3"));

        // Subsequent reads should be instant (cache hit)
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            memory.getAgentConfigs("Architect");
        }
        long elapsed = System.nanoTime() - start;

        // 1000 cache hits should take well under 100ms
        assertTrue(elapsed < 100_000_000, "Cache reads took too long: " + (elapsed / 1_000_000) + "ms");
    }

    @Test
    void saveAgentConfigWithStringParams() {
        memory.saveAgentConfig("SysAdmin", "llama3.1", "OLLAMA", "", "/project");

        AgentConfig config = memory.getAgentConfigs("SysAdmin");
        assertNotNull(config);
        assertEquals(Provider.OLLAMA, config.getProvider());
        assertEquals("llama3.1", config.getModelName());
    }

    @Test
    void saveAgentConfigInvalidProviderDefaultsToOllama() {
        memory.saveAgentConfig("SysAdmin", "model-x", "INVALID_PROVIDER", "", "/project");

        AgentConfig config = memory.getAgentConfigs("SysAdmin");
        assertNotNull(config);
        assertEquals(Provider.OLLAMA, config.getProvider());
    }

    // ==========================================================================
    // SHARED PATH TESTS — Goals
    // ==========================================================================

    @Test
    void addAndRetrieveGoals() {
        Goal goal = new Goal("Implement feature X");
        memory.addGoal("/project", goal);

        List<Goal> goals = memory.getGoals("/project");
        assertEquals(1, goals.size());
        assertEquals("Implement feature X", goals.get(0).getDescription());
    }

    @Test
    void updateGoalStatus() {
        Goal goal = new Goal("Fix bug Y");
        memory.addGoal("/project", goal);

        // Retrieve, modify status, and update
        Goal retrieved = memory.getGoals("/project").get(0);
        retrieved.setStatus(Goal.Status.IN_PROGRESS);
        memory.updateGoal("/project", retrieved);

        List<Goal> goals = memory.getGoals("/project");
        assertEquals(1, goals.size());
        assertEquals(Goal.Status.IN_PROGRESS, goals.get(0).getStatus());
    }

    @Test
    void setGoalsReplacesAll() {
        memory.addGoal("/project", new Goal("Goal 1"));
        memory.addGoal("/project", new Goal("Goal 2"));

        Goal replacement = new Goal("Replacement");
        replacement.setStatus(Goal.Status.COMPLETED);
        memory.setGoals("/project", List.of(replacement));

        List<Goal> goals = memory.getGoals("/project");
        assertEquals(1, goals.size());
        assertEquals("Replacement", goals.get(0).getDescription());
    }

    @Test
    void getGoalsForNonexistentProjectReturnsEmpty() {
        List<Goal> goals = memory.getGoals("/nonexistent");
        assertNotNull(goals);
        assertTrue(goals.isEmpty());
    }

    // ==========================================================================
    // SHARED PATH TESTS — Memories
    // ==========================================================================

    @Test
    void saveAndRetrieveMemory() {
        memory.saveMemory("/project/path", "This project uses Spring Boot with PostgreSQL");

        String result = memory.getMemory("/project/path");
        assertEquals("This project uses Spring Boot with PostgreSQL", result);
    }

    @Test
    void getMemoryReturnsEmptyForUnknownPath() {
        String result = memory.getMemory("/unknown/path");
        assertEquals("", result);
    }

    @Test
    void getAllMemories() {
        memory.saveMemory("/p1", "Memory 1");
        memory.saveMemory("/p2", "Memory 2");

        Map<String, String> all = memory.getAllMemories();
        assertEquals(2, all.size());
        assertEquals("Memory 1", all.get("/p1"));
    }

    // ==========================================================================
    // CACHED PATH TESTS — MCP Servers
    // ==========================================================================

    @Test
    void addAndRetrieveMcpServers() {
        McpServer server = new McpServer("Test Server", "http://localhost:3000", McpServer.McpType.API);
        memory.addMcpServer(server);

        List<McpServer> servers = memory.getMcpServers();
        assertEquals(1, servers.size());
        assertEquals("Test Server", servers.get(0).getName());
    }

    @Test
    void toggleMcpServer() {
        McpServer server = new McpServer("Test", "http://localhost:3000", McpServer.McpType.API);
        memory.addMcpServer(server);
        assertTrue(memory.getMcpServers().get(0).isEnabled());

        memory.toggleMcpServer(server.getId());
        assertFalse(memory.getMcpServers().get(0).isEnabled());
    }

    @Test
    void removeMcpServer() {
        McpServer s1 = new McpServer("Server 1", "http://a", McpServer.McpType.API);
        McpServer s2 = new McpServer("Server 2", "http://b", McpServer.McpType.CUSTOM);
        memory.addMcpServer(s1);
        memory.addMcpServer(s2);
        assertEquals(2, memory.getMcpServers().size());

        memory.removeMcpServer(s1.getId());
        assertEquals(1, memory.getMcpServers().size());
        assertEquals(s2.getId(), memory.getMcpServers().get(0).getId());
    }

    @Test
    void getEnabledMcpServersFiltersCorrectly() {
        McpServer s1 = new McpServer("Enabled", "http://a", McpServer.McpType.API);
        McpServer s2 = new McpServer("Disabled", "http://b", McpServer.McpType.BROWSER);
        s2.setEnabled(false);
        memory.addMcpServer(s1);
        memory.addMcpServer(s2);

        List<McpServer> enabled = memory.getEnabledMcpServers();
        assertEquals(1, enabled.size());
        assertEquals(s1.getId(), enabled.get(0).getId());
    }

    @Test
    void mcpServerCacheServedOnRepeatedReads() {
        memory.addMcpServer(new McpServer("Test", "http://a", McpServer.McpType.API));

        // Repeated reads should be cache hits
        long start = System.nanoTime();
        for (int i = 0; i < 500; i++) {
            memory.getMcpServers();
        }
        long elapsed = System.nanoTime() - start;
        assertTrue(elapsed < 50_000_000, "MCP server cache reads took too long: " + (elapsed / 1_000_000) + "ms");
    }

    // ==========================================================================
    // MULTI-INSTANCE SAFETY TESTS
    // ==========================================================================

    @Test
    void twoInstancesCanWriteConfigsWithoutCorruption() throws Exception {
        Path sharedDb = tempDir.resolve("multi").resolve("central_memory.db");
        Path localDb1 = tempDir.resolve("multi").resolve("instance1_stats.db");
        Path localDb2 = tempDir.resolve("multi").resolve("instance2_stats.db");

        // Close the default memory first
        memory.close();
        memory = null;

        CentralMemory instance1 = new CentralMemory(sharedDb, localDb1);
        CentralMemory instance2 = null;

        try {
            // Instance 1 writes a config
            instance1.saveAgentConfig("Coder", new AgentConfig(Provider.GEMINI, "gemini-2.0-flash"));
            instance1.close();

            // Instance 2 opens the same shared DB and reads it
            instance2 = new CentralMemory(sharedDb, localDb2);
            AgentConfig config = instance2.getAgentConfigs("Coder");

            assertNotNull(config, "Instance 2 should see Instance 1's config");
            assertEquals("gemini-2.0-flash", config.getModelName());
        } finally {
            if (instance2 != null) instance2.close();
        }
    }

    @Test
    void statsAreInstanceIsolated() throws Exception {
        Path sharedDb = tempDir.resolve("isolated").resolve("central_memory.db");
        Path localDb1 = tempDir.resolve("isolated").resolve("instance1_stats.db");
        Path localDb2 = tempDir.resolve("isolated").resolve("instance2_stats.db");

        // Close the default memory
        memory.close();
        memory = null;

        CentralMemory instance1 = new CentralMemory(sharedDb, localDb1);
        CentralMemory instance2 = new CentralMemory(sharedDb, localDb2);

        try {
            // Each instance writes stats to its own local DB
            instance1.saveAgentStat(createStat("Coder", true, 1000));
            instance1.saveAgentStat(createStat("Tester", true, 2000));

            instance2.saveAgentStat(createStat("Architect", true, 3000));

            // Each instance only sees its own stats
            assertEquals(2, instance1.getAgentStats().size());
            assertEquals(1, instance2.getAgentStats().size());
            assertEquals("Architect", instance2.getAgentStats().get(0).getAgentName());
        } finally {
            instance1.close();
            instance2.close();
        }
    }

    // ==========================================================================
    // LISTENER TESTS
    // ==========================================================================

    @Test
    void listenerNotifiedOnConfigSave() {
        AtomicInteger callCount = new AtomicInteger(0);
        memory.addListener((key, value) -> callCount.incrementAndGet());

        memory.saveAgentConfig("Coder", new AgentConfig(Provider.GEMINI, "flash"));
        assertEquals(1, callCount.get());
    }

    @Test
    void listenerNotifiedOnStatSave() {
        AtomicInteger callCount = new AtomicInteger(0);
        memory.addListener((key, value) -> callCount.incrementAndGet());

        memory.saveAgentStat(createStat("Coder", true, 100));
        assertEquals(1, callCount.get());
    }

    // ==========================================================================
    // REFRESH/SYNC TESTS
    // ==========================================================================

    @Test
    void refreshCacheReloadsFromSharedDb() throws Exception {
        Path sharedDb = tempDir.resolve("refresh").resolve("central_memory.db");
        Path localDb1 = tempDir.resolve("refresh").resolve("instance1_stats.db");
        Path localDb2 = tempDir.resolve("refresh").resolve("instance2_stats.db");

        memory.close();
        memory = null;

        CentralMemory instance1 = new CentralMemory(sharedDb, localDb1);
        CentralMemory instance2 = new CentralMemory(sharedDb, localDb2);

        try {
            // Instance 1 writes a config
            instance1.saveAgentConfig("DevOps", new AgentConfig(Provider.AZURE, "gpt-4o"));
            instance1.close();

            // Instance 2 doesn't have it in cache yet (loaded at construction time, before instance1 wrote)
            // But after refresh, it should appear
            instance2.refreshCache();
            AgentConfig config = instance2.getAgentConfigs("DevOps");
            assertNotNull(config, "After refreshCache, instance2 should see DevOps config");
            assertEquals("gpt-4o", config.getModelName());
        } finally {
            instance2.close();
        }
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private AgentStat createStat(String agentName, boolean success, long durationMs) {
        return new AgentStat(
                agentName,
                "GEMINI",
                "gemini-2.0-flash",
                durationMs,
                success,
                100,   // promptLength
                500,   // responseLength
                50,    // promptTokens
                100,   // candidateTokens
                150,   // totalTokens
                "session-" + System.nanoTime()
        );
    }
}
