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

public class SmartEventFilterPlugin extends BasePlugin {
    private final FilterConfig config;

    public SmartEventFilterPlugin(FilterConfig config) {
        super("smart_event_filter_plugin");
        this.config = (config != null) ? config : FilterConfig.defaults();
    }

    public FilterConfig getConfig() {
        return config;
    }

    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext callbackContext, LlmRequest.Builder llmRequestBuilder) {
        if (llmRequestBuilder == null) return Maybe.empty();
        List<Content> contents = llmRequestBuilder.build().contents();
        if (contents == null || contents.isEmpty()) return Maybe.empty();

        List<Content> filtered = filterContents(contents, this.config);
        llmRequestBuilder.contents(filtered);
        return Maybe.empty();
    }

    public static List<Content> filterContents(List<Content> contents, FilterConfig config) {
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
        int startIndex = (maxTurns > 0 && totalTurns > maxTurns) ? (totalTurns - maxTurns) : 0;

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
