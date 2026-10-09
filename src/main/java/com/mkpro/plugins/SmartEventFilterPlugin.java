package com.mkpro.plugins;

import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class SmartEventFilterPlugin extends BasePlugin {
    private final FilterConfig config;
    private final Set<String> initializedSessions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean sessionInitialized = new AtomicBoolean(false);
    private final AtomicBoolean manualCompactionRequested = new AtomicBoolean(false);

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
    }

    /**
     * Resets the session baseline state for a specific session ID.
     */
    public void resetSessionState(String sessionId) {
        if (sessionId != null && !sessionId.isEmpty()) {
            this.initializedSessions.remove(sessionId);
        }
        this.sessionInitialized.set(false);
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

    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext callbackContext, LlmRequest.Builder llmRequestBuilder) {
        if (llmRequestBuilder == null) return Maybe.empty();
        List<Content> contents = llmRequestBuilder.build().contents();
        if (contents == null || contents.isEmpty()) return Maybe.empty();

        String sessionId = (callbackContext != null) ? callbackContext.sessionId() : null;
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

        boolean manualRequested = this.manualCompactionRequested.get()
                || this.config.isManualCompactionRequested();
        boolean sessionFiltered = isSessionStartFiltered(sessionId);

        if (this.config.isPrefixCacheOptimized()) {
            if (!sessionFiltered || manualRequested) {
                List<Content> filtered = filterContents(contents, this.config, true);
                markSessionFiltered(sessionId);
                if (manualRequested) {
                    this.manualCompactionRequested.set(false);
                    this.config.setManualCompactionRequested(false);
                }
                return filtered;
            } else {
                // Subsequent active turn: Strictly append-only
                return contents;
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
     */
    public static List<Content> filterContents(List<Content> contents, FilterConfig config, boolean allowTurnTruncation) {
        if (contents == null || contents.isEmpty() || config == null) return contents;

        List<List<Content>> turns = new ArrayList<>();
        List<Content> currentTurn = null;
        for (Content c : contents) {
            if (isUserRole(c)) {
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
        int activeTurnIdx = totalTurns - 1;

        Content pinnedTurn0User = null;
        if (config.isPinInitialPrompt() && totalTurns > 1) {
            List<Content> turn0 = turns.get(0);
            if (!turn0.isEmpty() && isUserRole(turn0.get(0))) {
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
            boolean isActiveTurn = (i == activeTurnIdx);

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
                for (Content c : turn) {
                    if (c == pinnedTurn0User) continue;

                    if (config.isPinMemoryEvents() && hasMemoryTool(c)) {
                        Content processed = c;
                        if (config.isPruneToolOutputs()) {
                            processed = ToolOutputPruner.pruneContent(processed, config.getMaxToolPayloadChars());
                        }
                        result.add(processed);
                    } else if (config.isEvictStaleToolChurn() && isToolMessage(c)) {
                        // Evict stale tool messages
                        continue;
                    }
                }
            }
        }

        return result;
    }

    private static boolean isUserRole(Content content) {
        if (content == null) return false;
        return content.role().map(r -> "user".equalsIgnoreCase(r)).orElse(false);
    }

    private static boolean isToolMessage(Content content) {
        if (content == null) return false;
        if (content.parts().isPresent()) {
            for (Part p : content.parts().get()) {
                if (p.functionCall().isPresent() || p.functionResponse().isPresent()) {
                    return true;
                }
            }
        }
        return false;
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
