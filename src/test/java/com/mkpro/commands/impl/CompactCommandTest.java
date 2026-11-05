package com.mkpro.commands.impl;

import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.mkpro.CentralMemory;
import com.mkpro.agents.AgentManager;
import com.mkpro.commands.CommandRegistry;
import com.mkpro.core.MkProContext;
import com.mkpro.core.StoreKeys;
import com.mkpro.models.AgentConfig;
import com.mkpro.models.Provider;
import com.mkpro.models.RunnerType;
import com.mkpro.plugins.FilterConfig;
import com.mkpro.plugins.SmartEventFilterPlugin;
import com.mkpro.plugins.ToolOutputPruner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
        String scopedKey = CentralMemory.getCurrentProjectKeyPrefix() + StoreKeys.FILTER_CONFIG;

        // Test turn limits persistence
        command.execute(new String[]{"12"}, context);
        FilterConfig saved = CentralMemory.getInstance().getFilterConfig();
        assertNotNull(saved);
        assertEquals(12, saved.getMaxTurns());
        FilterConfig genericSaved = CentralMemory.getInstance().get(scopedKey, FilterConfig.class);
        assertNotNull(genericSaved);
        assertEquals(12, genericSaved.getMaxTurns());

        // Test prune settings persistence
        command.execute(new String[]{"prune", "3500"}, context);
        saved = CentralMemory.getInstance().getFilterConfig();
        assertNotNull(saved);
        assertTrue(saved.isPruneToolOutputs());
        assertEquals(3500, saved.getMaxToolPayloadChars());

        // Test prune off persistence
        command.execute(new String[]{"prune", "off"}, context);
        saved = CentralMemory.getInstance().getFilterConfig();
        assertNotNull(saved);
        assertFalse(saved.isPruneToolOutputs());

        // Test churn on/off persistence
        command.execute(new String[]{"churn", "on"}, context);
        saved = CentralMemory.getInstance().getFilterConfig();
        assertNotNull(saved);
        assertTrue(saved.isEvictStaleToolChurn());

        command.execute(new String[]{"churn", "off"}, context);
        saved = CentralMemory.getInstance().getFilterConfig();
        assertNotNull(saved);
        assertFalse(saved.isEvictStaleToolChurn());

        // Test default execute persistence
        saved.setMaxTurns(20);
        context.setFilterConfig(saved);
        command.execute(new String[0], context);
        saved = CentralMemory.getInstance().getFilterConfig();
        assertNotNull(saved);
        assertEquals(20, saved.getMaxTurns());
    }

    @Test
    @DisplayName("GEMINI provider: /compact rebuilds Runner and attaches SmartEventFilterPlugin with manual compaction requested")
    void testGeminiProviderRunnerRebuildAndCompactionFlag() throws Exception {
        agentConfigs.put("Coordinator", new AgentConfig(Provider.GEMINI, "gemini-2.5-flash"));
        agentConfigs.put("Coder", new AgentConfig(Provider.GEMINI, "gemini-2.5-flash"));

        InMemorySessionService sessionService = new InMemorySessionService();
        InMemoryArtifactService artifactService = new InMemoryArtifactService();
        context.setSessionService(sessionService);
        context.setArtifactService(artifactService);

        // Pass null apiKey to constructor to verify GEMINI key resolution fallback doesn't fail Runner creation
        AgentManager agentManager = new AgentManager(
                sessionService,
                artifactService,
                null,
                null,
                "http://localhost:11434",
                null,
                null,
                RunnerType.IN_MEMORY,
                null,
                null,
                null
        );
        context.setAgentManager(agentManager);

        command.execute(new String[]{"5"}, context);

        assertEquals(5, context.getMaxTurns());
        assertNotNull(context.getRunner(), "Runner must be built and non-null for GEMINI provider");
        SmartEventFilterPlugin plugin = context.getSmartEventFilterPlugin();
        assertNotNull(plugin, "SmartEventFilterPlugin must be attached to AgentManager for GEMINI provider");
        assertEquals(5, plugin.getConfig().getMaxTurns());
        assertTrue(plugin.getConfig().isManualCompactionRequested(),
                "Manual compaction flag must be requested after /compact <turns>");

        // Clear flag and run default /compact
        plugin.getConfig().setManualCompactionRequested(false);
        command.execute(new String[0], context);
        assertNotNull(context.getRunner());
        assertTrue(context.getFilterConfig().isManualCompactionRequested(),
                "Default /compact must request manual compaction");
    }

    @Test
    @DisplayName("GEMINI provider: /compact compacts active session events without orphaning user-role FunctionResponses")
    void testGeminiProviderSessionCompactionWithFunctionCallsAndResponses() throws Exception {
        agentConfigs.put("Coordinator", new AgentConfig(Provider.GEMINI, "gemini-2.5-flash"));

        InMemorySessionService sessionService = new InMemorySessionService();
        InMemoryArtifactService artifactService = new InMemoryArtifactService();
        context.setSessionService(sessionService);
        context.setArtifactService(artifactService);

        AgentManager agentManager = new AgentManager(
                sessionService,
                artifactService,
                null,
                "test-gemini-key",
                "http://localhost:11434",
                null,
                null,
                RunnerType.IN_MEMORY,
                null,
                null,
                null
        );
        context.setAgentManager(agentManager);

        SessionKey sessionKey = new SessionKey("mkpro", "Coordinator", "gemini-session");
        Session session = sessionService.createSession(sessionKey, new HashMap<>()).blockingGet();

        // Turn 0: initial user goal + model reply
        appendEvent(sessionService, session, "user", userTextContent("Turn 0 initial goal"));
        appendEvent(sessionService, session, "Coordinator", modelTextContent("Turn 0 model ack"));

        // Turn 1 (stale turn with Gemini FunctionCall and role="user" FunctionResponse)
        appendEvent(sessionService, session, "user", userTextContent("Turn 1 request"));
        appendEvent(sessionService, session, "Coordinator", modelFunctionCallContent("call-1", "ask_coder", Map.of("instruction", "inspect")));
        appendEvent(sessionService, session, "Coordinator", geminiFunctionResponseContent("call-1", "ask_coder", "stale output"));
        appendEvent(sessionService, session, "Coordinator", modelTextContent("Turn 1 answer"));

        // Turn 2 (active window turn with Gemini FunctionCall and oversized role="user" FunctionResponse)
        String oversizedPayload = "A".repeat(3000);
        appendEvent(sessionService, session, "user", userTextContent("Turn 2 active request"));
        appendEvent(sessionService, session, "Coordinator", modelFunctionCallContent("call-2", "ask_coder", Map.of("instruction", "implement")));
        appendEvent(sessionService, session, "Coordinator", geminiFunctionResponseContent("call-2", "ask_coder", oversizedPayload));
        appendEvent(sessionService, session, "Coordinator", modelTextContent("Turn 2 final answer"));

        context.setCurrentSession(session);

        // Compact down to last 1 turn
        command.execute(new String[]{"1"}, context);

        Session compactedSession = context.getCurrentSession();
        assertNotNull(compactedSession);
        List<Event> compactedEvents = compactedSession.immutableEvents();

        // Expected: pinned Turn 0 user prompt (1 event) + Turn 2 (4 events: user, FC, FR, model) = 5 events
        assertEquals(5, compactedEvents.size(), "Compacted GEMINI session should contain pinned Turn 0 prompt + 4 events of Turn 2");

        // Verify Turn 0 initial prompt is pinned
        assertEquals("Turn 0 initial goal", compactedEvents.get(0).content().get().text());

        // Verify Turn 1 was completely evicted (no orphaned FunctionResponse from Turn 1)
        for (Event ev : compactedEvents) {
            ev.content().flatMap(Content::parts).ifPresent(parts -> {
                for (Part p : parts) {
                    p.functionResponse().ifPresent(fr ->
                            assertNotEquals("call-1", fr.id().orElse(""), "Stale Turn 1 FunctionResponse must not be orphaned"));
                }
            });
        }

        // Verify Turn 2 FunctionCall and FunctionResponse remain paired in order and pruned
        assertTrue(compactedEvents.get(2).content().get().parts().get().get(0).functionCall().isPresent(),
                "Event 2 must be the Turn 2 model FunctionCall");
        Part frPart = compactedEvents.get(3).content().get().parts().get().get(0);
        assertTrue(frPart.functionResponse().isPresent(), "Event 3 must be the Turn 2 FunctionResponse");
        String prunedResult = (String) frPart.functionResponse().get().response().get().get("result");
        assertTrue(prunedResult.contains(ToolOutputPruner.TRUNCATION_MARKER_PREFIX),
                "Oversized FunctionResponse payload in active window must be pruned");

        // Verify sessionService also holds the compacted session
        Session storedSession = sessionService.getSession(sessionKey, GetSessionConfig.builder().build()).blockingGet();
        assertNotNull(storedSession);
        assertEquals(5, storedSession.immutableEvents().size());
    }

    @Test
    @DisplayName("GEMINI provider: multi-step tool loop preserves compacted baseline across subsequent model calls")
    void testGeminiProviderMultiStepToolLoopPreservesCompactedBaseline() throws Exception {
        agentConfigs.put("Coordinator", new AgentConfig(Provider.GEMINI, "gemini-2.5-flash"));

        InMemorySessionService sessionService = new InMemorySessionService();
        InMemoryArtifactService artifactService = new InMemoryArtifactService();
        context.setSessionService(sessionService);
        context.setArtifactService(artifactService);

        AgentManager agentManager = new AgentManager(
                sessionService,
                artifactService,
                null,
                "test-gemini-key",
                "http://localhost:11434",
                null,
                null,
                RunnerType.IN_MEMORY,
                null,
                null,
                null
        );
        context.setAgentManager(agentManager);

        command.execute(new String[]{"1"}, context);
        SmartEventFilterPlugin plugin = context.getSmartEventFilterPlugin();
        assertNotNull(plugin);

        // Historical contents with Gemini role="user" FunctionResponse in Turn 0 and Turn 1
        Content t0User = userTextContent("Turn 0 user");
        Content t0Fc = modelFunctionCallContent("c0", "ask_coder", Map.of("instruction", "step0"));
        Content t0Fr = geminiFunctionResponseContent("c0", "ask_coder", "resp0");
        Content t0Model = modelTextContent("Turn 0 model");

        Content t1User = userTextContent("Turn 1 user");
        Content t1Model = modelTextContent("Turn 1 model");

        Content t2User = userTextContent("Turn 2 user");

        List<Content> step1Contents = List.of(t0User, t0Fc, t0Fr, t0Model, t1User, t1Model, t2User);
        LlmRequest.Builder step1Req = LlmRequest.builder().contents(step1Contents);
        plugin.beforeModelCallback(null, step1Req);

        List<Content> step1Filtered = step1Req.build().contents();
        // Should only have pinned t0User + t2User (2 items); t0Fc, t0Fr, t0Model, t1User, t1Model compacted away
        assertEquals(2, step1Filtered.size());
        assertEquals(t0User, step1Filtered.get(0));
        assertEquals(t2User, step1Filtered.get(1));

        // Simulate Step 2 of Turn 2 in GEMINI (after model emits FunctionCall and tool returns FunctionResponse):
        // Even if Contents processor rebuilds from full uncompacted history + new tool call/response,
        // the compacted baseline must be preserved!
        Content t2Fc = modelFunctionCallContent("c2", "ask_coder", Map.of("instruction", "step2"));
        Content t2Fr = geminiFunctionResponseContent("c2", "ask_coder", "resp2");
        List<Content> step2RebuiltFromFullSession = new ArrayList<>(step1Contents);
        step2RebuiltFromFullSession.add(t2Fc);
        step2RebuiltFromFullSession.add(t2Fr);

        LlmRequest.Builder step2Req = LlmRequest.builder().contents(step2RebuiltFromFullSession);
        plugin.beforeModelCallback(null, step2Req);

        List<Content> step2Filtered = step2Req.build().contents();
        assertEquals(4, step2Filtered.size(), "Step 2 must keep compacted baseline [t0User, t2User] + appended [t2Fc, t2Fr]");
        assertEquals(t0User, step2Filtered.get(0));
        assertEquals(t2User, step2Filtered.get(1));
        assertEquals(t2Fc, step2Filtered.get(2));
        assertEquals(t2Fr, step2Filtered.get(3));
        assertFalse(step2Filtered.contains(t1User), "Compacted Turn 1 must not leak back on Step 2");
        assertFalse(step2Filtered.contains(t0Fr), "Compacted Turn 0 FunctionResponse must not leak back on Step 2");
    }

    @Test
    @DisplayName("GEMINI provider: subcommands prune, churn, off, and filter work cleanly")
    void testGeminiProviderSubcommandsPruneChurnAndOff() throws Exception {
        agentConfigs.put("Coordinator", new AgentConfig(Provider.GEMINI, "gemini-2.5-flash"));

        InMemorySessionService sessionService = new InMemorySessionService();
        InMemoryArtifactService artifactService = new InMemoryArtifactService();
        context.setSessionService(sessionService);
        context.setArtifactService(artifactService);

        AgentManager agentManager = new AgentManager(
                sessionService,
                artifactService,
                null,
                "test-gemini-key",
                "http://localhost:11434",
                null,
                null,
                RunnerType.IN_MEMORY,
                null,
                null,
                null
        );
        context.setAgentManager(agentManager);

        command.execute(new String[]{"prune", "1200"}, context);
        assertTrue(context.getFilterConfig().isPruneToolOutputs());
        assertEquals(1200, context.getFilterConfig().getMaxToolPayloadChars());
        assertNotNull(context.getRunner());

        command.execute(new String[]{"churn", "on"}, context);
        assertTrue(context.getFilterConfig().isEvictStaleToolChurn());
        assertNotNull(context.getRunner());

        command.execute(new String[]{"off"}, context);
        assertEquals(-1, context.getMaxTurns());
        assertFalse(context.getFilterConfig().isManualCompactionRequested());
        assertNotNull(context.getRunner());

        assertDoesNotThrow(() -> command.execute(new String[]{"filter"}, context));
    }

    private static void appendEvent(InMemorySessionService service, Session session, String author, Content content) {
        Event ev = Event.builder()
                .id(java.util.UUID.randomUUID().toString())
                .invocationId(java.util.UUID.randomUUID().toString())
                .author(author)
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
        service.appendEvent(session, ev).blockingGet();
    }

    private static Content userTextContent(String text) {
        return Content.builder().role("user").parts(List.of(Part.fromText(text))).build();
    }

    private static Content modelTextContent(String text) {
        return Content.builder().role("model").parts(List.of(Part.fromText(text))).build();
    }

    private static Content modelFunctionCallContent(String id, String name, Map<String, Object> args) {
        FunctionCall fc = FunctionCall.builder().id(id).name(name).args(args).build();
        return Content.builder().role("model").parts(List.of(Part.builder().functionCall(fc).build())).build();
    }

    private static Content geminiFunctionResponseContent(String id, String name, String resultText) {
        FunctionResponse fr = FunctionResponse.builder()
                .id(id)
                .name(name)
                .response(Map.of("result", resultText))
                .build();
        // ADK / Gemini uses role="user" for FunctionResponse events
        return Content.builder().role("user").parts(List.of(Part.builder().functionResponse(fr).build())).build();
    }
}