package com.mkpro.plugins;

import com.google.adk.agents.CallbackContext;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class SmartEventFilterPlugin extends BasePlugin {
    private static final String DEFAULT_SESSION_KEY = "__default_session__";

    private final FilterConfig config;
    private final Set<String> initializedSessions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean sessionInitialized = new AtomicBoolean(false);
    private final AtomicBoolean manualCompactionRequested = new AtomicBoolean(false);
    private final Map<String, List<Content>> baselineOriginalContents = new ConcurrentHashMap<>();
    private final Map<String, List<Content>> baselineFilteredContents = new ConcurrentHashMap<>();

    public SmartEventFilterPlugin(FilterConfig config) {
        super("smart_event_filter_plugin");
        this.config = (config != null) ? config : FilterConfig.defaults();
    }

    public FilterConfig getConfig() {
        return config;
    }

    /**
     * Flags a one-time manual compaction to establish a new compacted baseline
     * on the next model call.
     */
    public void requestManualCompaction() {
        this.manualCompactionRequested.set(true);
        if (this.config != null) {
            this.config.requestManualCompaction();
        }
    }

    /**
     * Resets the session baseline state across all sessions.
     */
    public void resetSessionState() {
        this.sessionInitialized.set(false);
        this.initializedSessions.clear();
        this.baselineOriginalContents.clear();
        this.baselineFilteredContents.clear();
    }

    /**
     * Resets the session baseline state for a specific session ID.
     */
    public void resetSessionState(String sessionId) {
        if (sessionId != null && !sessionId.isEmpty()) {
            this.initializedSessions.remove(sessionId);
            this.baselineOriginalContents.remove(sessionId);
            this.baselineFilteredContents.remove(sessionId);
        }
        this.sessionInitialized.set(false);
        this.baselineOriginalContents.remove(DEFAULT_SESSION_KEY);
        this.baselineFilteredContents.remove(DEFAULT_SESSION_KEY);
    }

    /**
     * Checks if session start filtering has occurred for any session.
     */
    public boolean isSessionStartFiltered() {
        return this.sessionInitialized.get() || !this.initializedSessions.isEmpty();
    }

    /**
     * Checks if session start filtering has occurred for the given session ID.
     */
    public boolean isSessionStartFiltered(String sessionId) {
        if (sessionId != null && !sessionId.isEmpty()) {
            return this.initializedSessions.contains(sessionId);
        }
        return this.sessionInitialized.get();
    }

    /**
     * Marks the given session ID (and global flag) as filtered.
     */
    public void markSessionFiltered(String sessionId) {
        if (sessionId != null && !sessionId.isEmpty()) {
            this.initializedSessions.add(sessionId);
        }
        this.sessionInitialized.set(true);
    }

    private List<Content> applyBaselinePrefix(String baselineKey, List<Content> contents) {
        if (contents == null || contents.isEmpty()) return contents;
        List<Content> baseOrig = this.baselineOriginalContents.get(baselineKey);
        List<Content> baseFilt = this.baselineFilteredContents.get(baselineKey);
        if (baseOrig != null && baseFilt != null && !baseOrig.isEmpty() && !baseOrig.equals(baseFilt)) {
            if (contents.size() >= baseOrig.size() && contents.subList(0, baseOrig.size()).equals(baseOrig)) {
                List<Content> effective = new ArrayList<>(baseFilt.size() + (contents.size() - baseOrig.size()));
                effective.addAll(baseFilt);
                effective.addAll(contents.subList(baseOrig.size(), contents.size()));
                return effective;
            }
        }
        return contents;
    }

    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext callbackContext, LlmRequest.Builder llmRequestBuilder) {
        if (llmRequestBuilder == null) return Maybe.empty();
        List<Content> contents = llmRequestBuilder.build().contents();
        if (contents == null || contents.isEmpty()) return Maybe.empty();

        String sessionId = (callbackContext != null) ? callbackContext.sessionId() : null;
        String baselineKey = (sessionId != null && !sessionId.isEmpty()) ? sessionId : DEFAULT_SESSION_KEY;
        boolean manualRequested = this.manualCompactionRequested.get()
                || (this.config != null && this.config.isManualCompactionRequested());
        boolean sessionFiltered = isSessionStartFiltered(sessionId);

        if (this.config != null && this.config.isPrefixCacheOptimized()) {
            // SESSION_START_COMPACTION_PREFIX_CACHE_STRATEGY:
            // At session start (the first model call) or upon manual compaction:
            // Apply filtering/pruning once to establish the baseline.
            if (!sessionFiltered || manualRequested) {
                List<Content> filtered = filterContents(contents, this.config, true);
                llmRequestBuilder.contents(filtered);
                this.baselineOriginalContents.put(baselineKey, new ArrayList<>(contents));
                this.baselineFilteredContents.put(baselineKey, new ArrayList<>(filtered));
                markSessionFiltered(sessionId);

                if (manualRequested) {
                    this.manualCompactionRequested.set(false);
                    if (this.config != null) {
                        this.config.setManualCompactionRequested(false);
                    }
                }
            } else {
                // For subsequent turns during the active conversation:
                // Keep history strictly append-only (no sliding window truncation,
                // no mutating previously sent turns), preserving the exact KV prefix cache!
                // If the runner rebuilt contents from full session history containing the
                // pre-compaction baseline prefix, substitute the compacted baseline prefix.
                List<Content> effective = applyBaselinePrefix(baselineKey, contents);
                if (effective != contents) {
                    llmRequestBuilder.contents(effective);
                }
            }
        } else {
            // Traditional sliding window or continuous filtering mode:
            boolean allowTruncation = (this.config == null || this.config.isSlidingWindowEnabled());
            List<Content> filtered = filterContents(contents, this.config, allowTruncation);
            llmRequestBuilder.contents(filtered);
            markSessionFiltered(sessionId);
        }

        return Maybe.empty();
    }

    /**
     * Instance filterContents using configured settings and session baseline state.
     */
    public List<Content> filterContents(List<Content> contents) {
        return filterContents((String) null, contents);
    }

    /**
     * Instance filterContents tracking baseline state for a specific session ID.
     */
    public List<Content> filterContents(String sessionId, List<Content> contents) {
        if (contents == null || contents.isEmpty() || this.config == null) return contents;

        String baselineKey = (sessionId != null && !sessionId.isEmpty()) ? sessionId : DEFAULT_SESSION_KEY;
        boolean manualRequested = this.manualCompactionRequested.get()
                || this.config.isManualCompactionRequested();
        boolean sessionFiltered = isSessionStartFiltered(sessionId);

        if (this.config.isPrefixCacheOptimized()) {
            if (!sessionFiltered || manualRequested) {
                List<Content> filtered = filterContents(contents, this.config, true);
                this.baselineOriginalContents.put(baselineKey, new ArrayList<>(contents));
                this.baselineFilteredContents.put(baselineKey, new ArrayList<>(filtered));
                markSessionFiltered(sessionId);
                if (manualRequested) {
                    this.manualCompactionRequested.set(false);
                    this.config.setManualCompactionRequested(false);
                }
                return filtered;
            } else {
                // Subsequent active turn: Strictly append-only on top of compacted baseline
                return applyBaselinePrefix(baselineKey, contents);
            }
        } else {
            boolean allowTruncation = this.config.isSlidingWindowEnabled();
            List<Content> filtered = filterContents(contents, this.config, allowTruncation);
            markSessionFiltered(sessionId);
            return filtered;
        }
    }

    /**
     * Static filter method for direct invocation (backward compatible with full compaction rules).
     */
    public static List<Content> filterContents(List<Content> contents, FilterConfig config) {
        return filterContents(contents, config, true);
    }

    /**
     * Overloaded static filter method with control over turn truncation.
     * Ensures FunctionCall and FunctionResponse pairs (where ADK/Gemini uses role="user" for
     * FunctionResponse) remain grouped within the same user invocation turn.
     */
    public static List<Content> filterContents(List<Content> contents, FilterConfig config, boolean allowTurnTruncation) {
        if (contents == null || contents.isEmpty() || config == null) return contents;

        List<List<Content>> turns = new ArrayList<>();
        List<Content> currentTurn = null;
        for (Content c : contents) {
            if (isUserTurnStart(c)) {
                currentTurn = new ArrayList<>();
                turns.add(currentTurn);
            } else if (currentTurn == null) {
                currentTurn = new ArrayList<>();
                turns.add(currentTurn);
            }
            currentTurn.add(c);
        }

        int totalTurns = turns.size();
        if (totalTurns == 0) return contents;

        Content pinnedTurn0User = null;
        if (config.isPinInitialPrompt() && totalTurns > 1) {
            List<Content> turn0 = turns.get(0);
            if (!turn0.isEmpty() && isUserTurnStart(turn0.get(0))) {
                pinnedTurn0User = turn0.get(0);
            }
        }

        int maxTurns = config.getMaxTurns();
        int startIndex = (allowTurnTruncation && maxTurns > 0 && totalTurns > maxTurns) ? (totalTurns - maxTurns) : 0;

        List<Content> result = new ArrayList<>();

        if (pinnedTurn0User != null && startIndex > 0) {
            result.add(pinnedTurn0User);
        }

        for (int i = 0; i < totalTurns; i++) {
            List<Content> turn = turns.get(i);
            boolean inWindow = (i >= startIndex);

            if (inWindow) {
                for (Content c : turn) {
                    if (pinnedTurn0User != null && c == pinnedTurn0User && startIndex > 0 && i == 0) {
                        continue;
                    }

                    Content processed = c;
                    if (config.isPruneToolOutputs()) {
                        processed = ToolOutputPruner.pruneContent(processed, config.getMaxToolPayloadChars());
                    }
                    result.add(processed);
                }
            } else {
                // Outside active window
                boolean[] addedInTurn = new boolean[turn.size()];
                for (int j = 0; j < turn.size(); j++) {
                    if (addedInTurn[j]) continue;
                    Content c = turn.get(j);
                    if (c == pinnedTurn0User) continue;

                    if (config.isPinMemoryEvents() && hasMemoryTool(c)) {
                        boolean isGeminiToolPair = hasFunctionCall(c) || (isUserRole(c) && hasFunctionResponse(c));
                        if (isGeminiToolPair) {
                            // Ensure preceding user turn start is present if result does not end with a user prompt
                            if ((result.isEmpty() || !isUserTurnStart(result.get(result.size() - 1)))
                                    && !turn.isEmpty() && isUserTurnStart(turn.get(0))
                                    && turn.get(0) != pinnedTurn0User && !addedInTurn[0]) {
                                result.add(pruneIfConfigured(turn.get(0), config));
                                addedInTurn[0] = true;
                            }
                            // If c is a FunctionResponse, ensure its preceding FunctionCall in this turn is included
                            if (hasFunctionResponse(c) && j > 0 && hasFunctionCall(turn.get(j - 1)) && !addedInTurn[j - 1]) {
                                result.add(pruneIfConfigured(turn.get(j - 1), config));
                                addedInTurn[j - 1] = true;
                            }
                            if (!addedInTurn[j]) {
                                result.add(pruneIfConfigured(c, config));
                                addedInTurn[j] = true;
                            }
                            // If c is a FunctionCall, include its following FunctionResponse in this turn
                            int respIdx = hasFunctionResponse(c) ? j : -1;
                            if (hasFunctionCall(c) && j + 1 < turn.size() && hasFunctionResponse(turn.get(j + 1)) && !addedInTurn[j + 1]) {
                                result.add(pruneIfConfigured(turn.get(j + 1), config));
                                addedInTurn[j + 1] = true;
                                respIdx = j + 1;
                            }
                            // Ensure closing model response follows a user-role FunctionResponse before next user turn
                            if (respIdx >= 0 && respIdx + 1 < turn.size() && !addedInTurn[respIdx + 1]) {
                                Content nextInTurn = turn.get(respIdx + 1);
                                if (isModelRole(nextInTurn) && !hasFunctionCall(nextInTurn)) {
                                    result.add(pruneIfConfigured(nextInTurn, config));
                                    addedInTurn[respIdx + 1] = true;
                                }
                            }
                        } else {
                            result.add(pruneIfConfigured(c, config));
                            addedInTurn[j] = true;
                        }
                    } else if (config.isEvictStaleToolChurn() && isToolMessage(c)) {
                        // Evict stale tool messages
                        continue;
                    }
                }
            }
        }

        return result;
    }

    /**
     * Filters a list of ADK {@link Event} objects according to {@link FilterConfig},
     * preserving FunctionCall / FunctionResponse pairing within invocation turns.
     */
    public static List<Event> filterEvents(List<Event> events, FilterConfig config) {
        if (events == null || events.isEmpty() || config == null) return events;

        List<List<Event>> turns = new ArrayList<>();
        List<Event> currentTurn = null;
        for (Event ev : events) {
            Content c = ev != null ? ev.content().orElse(null) : null;
            if (c != null && isUserTurnStart(c)) {
                currentTurn = new ArrayList<>();
                turns.add(currentTurn);
            } else if (currentTurn == null) {
                currentTurn = new ArrayList<>();
                turns.add(currentTurn);
            }
            currentTurn.add(ev);
        }

        int totalTurns = turns.size();
        if (totalTurns == 0) return events;

        Event pinnedTurn0User = null;
        if (config.isPinInitialPrompt() && totalTurns > 1) {
            List<Event> turn0 = turns.get(0);
            if (!turn0.isEmpty()) {
                Content c0 = turn0.get(0) != null ? turn0.get(0).content().orElse(null) : null;
                if (c0 != null && isUserTurnStart(c0)) {
                    pinnedTurn0User = turn0.get(0);
                }
            }
        }

        int maxTurns = config.getMaxTurns();
        int startIndex = (maxTurns > 0 && totalTurns > maxTurns) ? (totalTurns - maxTurns) : 0;

        List<Event> result = new ArrayList<>();

        if (pinnedTurn0User != null && startIndex > 0) {
            result.add(pinnedTurn0User);
        }

        for (int i = 0; i < totalTurns; i++) {
            List<Event> turn = turns.get(i);
            boolean inWindow = (i >= startIndex);

            if (inWindow) {
                for (Event ev : turn) {
                    if (pinnedTurn0User != null && ev == pinnedTurn0User && startIndex > 0 && i == 0) {
                        continue;
                    }
                    result.add(pruneEventIfConfigured(ev, config));
                }
            } else {
                boolean[] addedInTurn = new boolean[turn.size()];
                for (int j = 0; j < turn.size(); j++) {
                    if (addedInTurn[j]) continue;
                    Event ev = turn.get(j);
                    if (ev == pinnedTurn0User) continue;
                    Content c = ev != null ? ev.content().orElse(null) : null;

                    if (config.isPinMemoryEvents() && c != null && hasMemoryTool(c)) {
                        boolean isGeminiToolPair = hasFunctionCall(c) || (isUserRole(c) && hasFunctionResponse(c));
                        if (isGeminiToolPair) {
                            Content lastResultContent = result.isEmpty() ? null : result.get(result.size() - 1).content().orElse(null);
                            Content firstTurnContent = !turn.isEmpty() && turn.get(0) != null ? turn.get(0).content().orElse(null) : null;
                            if ((lastResultContent == null || !isUserTurnStart(lastResultContent))
                                    && firstTurnContent != null && isUserTurnStart(firstTurnContent)
                                    && turn.get(0) != pinnedTurn0User && !addedInTurn[0]) {
                                result.add(pruneEventIfConfigured(turn.get(0), config));
                                addedInTurn[0] = true;
                            }
                            if (hasFunctionResponse(c) && j > 0 && !addedInTurn[j - 1]) {
                                Content prevC = turn.get(j - 1) != null ? turn.get(j - 1).content().orElse(null) : null;
                                if (prevC != null && hasFunctionCall(prevC)) {
                                    result.add(pruneEventIfConfigured(turn.get(j - 1), config));
                                    addedInTurn[j - 1] = true;
                                }
                            }
                            if (!addedInTurn[j]) {
                                result.add(pruneEventIfConfigured(ev, config));
                                addedInTurn[j] = true;
                            }
                            int respIdx = hasFunctionResponse(c) ? j : -1;
                            if (hasFunctionCall(c) && j + 1 < turn.size() && !addedInTurn[j + 1]) {
                                Content nextC = turn.get(j + 1) != null ? turn.get(j + 1).content().orElse(null) : null;
                                if (nextC != null && hasFunctionResponse(nextC)) {
                                    result.add(pruneEventIfConfigured(turn.get(j + 1), config));
                                    addedInTurn[j + 1] = true;
                                    respIdx = j + 1;
                                }
                            }
                            if (respIdx >= 0 && respIdx + 1 < turn.size() && !addedInTurn[respIdx + 1]) {
                                Content afterResp = turn.get(respIdx + 1) != null ? turn.get(respIdx + 1).content().orElse(null) : null;
                                if (afterResp != null && isModelRole(afterResp) && !hasFunctionCall(afterResp)) {
                                    result.add(pruneEventIfConfigured(turn.get(respIdx + 1), config));
                                    addedInTurn[respIdx + 1] = true;
                                }
                            }
                        } else {
                            result.add(pruneEventIfConfigured(ev, config));
                            addedInTurn[j] = true;
                        }
                    } else if (config.isEvictStaleToolChurn() && c != null && isToolMessage(c)) {
                        continue;
                    }
                }
            }
        }

        return result;
    }

    /**
     * Compacts the events of an active {@link Session} and persists the compacted state to
     * {@link BaseSessionService} if provided.
     */
    @SuppressWarnings("deprecation")
    public static Session compactSession(Session session, BaseSessionService sessionService, FilterConfig config) {
        if (session == null || config == null) return session;

        Session stored = null;
        if (sessionService != null) {
            try {
                stored = sessionService.getSession(session.sessionKey(), GetSessionConfig.builder().build()).blockingGet();
            } catch (Exception ignored) {
            }
        }

        List<Event> sourceEvents = session.immutableEvents();
        if (stored != null && stored.immutableEvents().size() > sourceEvents.size()) {
            sourceEvents = stored.immutableEvents();
        }
        if (sourceEvents.isEmpty()) {
            return stored != null ? stored : session;
        }

        List<Event> compactedEvents = filterEvents(sourceEvents, config);

        synchronized (session.events()) {
            session.events().clear();
            session.events().addAll(compactedEvents);
        }

        if (sessionService != null) {
            try {
                String appName = session.appName() != null ? session.appName() : "mkpro";
                String userId = session.userId() != null ? session.userId() : "Coordinator";
                String sessionId = session.id();
                Map<String, Object> stateCopy = new HashMap<>(stored != null ? stored.state() : session.state());
                sessionService.deleteSession(appName, userId, sessionId).blockingAwait();
                Session recreated = sessionService.createSession(appName, userId, stateCopy, sessionId).blockingGet();
                for (Event ev : compactedEvents) {
                    sessionService.appendEvent(recreated, ev).blockingGet();
                }
                return recreated;
            } catch (Exception ignored) {
            }
        }

        return session;
    }

    private static Content pruneIfConfigured(Content content, FilterConfig config) {
        if (content != null && config != null && config.isPruneToolOutputs()) {
            return ToolOutputPruner.pruneContent(content, config.getMaxToolPayloadChars());
        }
        return content;
    }

    private static Event pruneEventIfConfigured(Event event, FilterConfig config) {
        if (event != null && config != null && config.isPruneToolOutputs() && event.content().isPresent()) {
            Content original = event.content().get();
            Content pruned = ToolOutputPruner.pruneContent(original, config.getMaxToolPayloadChars());
            if (pruned != original) {
                return event.toBuilder().content(pruned).build();
            }
        }
        return event;
    }

    private static boolean isUserRole(Content content) {
        if (content == null) return false;
        return content.role().map(r -> "user".equalsIgnoreCase(r)).orElse(false);
    }

    private static boolean isModelRole(Content content) {
        if (content == null) return false;
        return content.role().map(r -> "model".equalsIgnoreCase(r) || "assistant".equalsIgnoreCase(r)).orElse(false);
    }

    private static boolean isUserTurnStart(Content content) {
        return isUserRole(content) && !hasFunctionResponse(content);
    }

    private static boolean hasFunctionCall(Content content) {
        if (content == null || !content.parts().isPresent()) return false;
        for (Part p : content.parts().get()) {
            if (p.functionCall().isPresent()) return true;
        }
        return false;
    }

    private static boolean hasFunctionResponse(Content content) {
        if (content == null || !content.parts().isPresent()) return false;
        for (Part p : content.parts().get()) {
            if (p.functionResponse().isPresent()) return true;
        }
        return false;
    }

    private static boolean isToolMessage(Content content) {
        return hasFunctionCall(content) || hasFunctionResponse(content);
    }

    private static boolean hasMemoryTool(Content content) {
        if (content == null) return false;
        if (content.parts().isPresent()) {
            for (Part p : content.parts().get()) {
                if (p.functionCall().isPresent()) {
                    String name = p.functionCall().get().name().orElse("");
                    if (isMemoryToolName(name)) return true;
                }
                if (p.functionResponse().isPresent()) {
                    String name = p.functionResponse().get().name().orElse("");
                    if (isMemoryToolName(name)) return true;
                }
            }
        }
        return false;
    }

    private static boolean isMemoryToolName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.contains("memory") || lower.contains("remember") || lower.contains("memorize") || lower.contains("recall") || lower.contains("fact");
    }
}
