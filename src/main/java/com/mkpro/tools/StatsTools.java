package com.mkpro.tools;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;
import com.mkpro.CentralMemory;
import com.mkpro.models.AgentStat;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public class StatsTools {

    public static BaseTool createGetSessionStatsTool() {
        return new BaseTool("get_session_stats", "Retrieves a detailed breakdown of token usage for the current active session, including per-agent and per-model consumption.") {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(Map.of("sessionId", Schema.builder().type("STRING").description("The ID of the current session").build()))
                                .required(List.of("sessionId"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    final String reqSessionId = (String) args.get("sessionId");
                    List<AgentStat> allStats = CentralMemory.getInstance().getAgentStats();
                    
                    // Filter stats
                    List<AgentStat> stats = allStats.stream()
                            .filter(s -> reqSessionId.equals(s.getSessionId()))
                            .collect(Collectors.toList());

                    String finalSessionId = reqSessionId;

                    // Fallback: If no stats match, and it's looking for "current" or "default", grab the latest active session.
                    if (stats.isEmpty()) {
                        String latestSessionId = allStats.stream()
                                .map(AgentStat::getSessionId)
                                .filter(Objects::nonNull)
                                .reduce((first, second) -> second) // Get last item
                                .orElse("unknown");
                        
                        stats = allStats.stream()
                                .filter(s -> latestSessionId.equals(s.getSessionId()))
                                .collect(Collectors.toList());
                        finalSessionId = latestSessionId + " (auto-resolved)";
                    }

                    final String reportSessionId = finalSessionId;

                    long totalTokens = stats.stream().mapToLong(AgentStat::getTotalTokens).sum();

                    if (stats.isEmpty() || totalTokens == 0) {
                        return ImmutableMap.of("result", "Session '" + reportSessionId + "' has no recorded token consumption yet.");
                    }

                    Map<String, Long> agentTokens = stats.stream()
                            .collect(Collectors.groupingBy(AgentStat::getAgentName, Collectors.summingLong(AgentStat::getTotalTokens)));

                    Map<String, Long> modelTokens = stats.stream()
                            .collect(Collectors.groupingBy(AgentStat::getModel, Collectors.summingLong(AgentStat::getTotalTokens)));

                    StringBuilder report = new StringBuilder();
                    report.append(String.format("Session '%s' has consumed %,d tokens in total.\n", reportSessionId, totalTokens));
                    
                    report.append("Breakdown by Agent:\n");
                    agentTokens.forEach((agent, tokens) -> report.append(String.format(" - %s: %,d tokens\n", agent, tokens)));

                    report.append("Breakdown by Model:\n");
                    modelTokens.forEach((model, tokens) -> report.append(String.format(" - %s: %,d tokens\n", model, tokens)));

                    return ImmutableMap.of("result", report.toString());
                });
            }
        };
    }
}
