package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.models.AgentStat;
import com.mkpro.MkPro;
import java.util.*;
import java.util.stream.Collectors;
import java.io.PrintWriter;

public class StatsCommand implements Command {
    @Override
    public void execute(String[] args, MkProContext context) {
        List<AgentStat> allStats = context.getCentralMemory().getAgentStats();
        PrintWriter out = context.getTerminal() != null 
            ? context.getTerminal().writer() 
            : new PrintWriter(System.out, true);

        if (allStats == null || allStats.isEmpty()) {
            out.println(MkPro.ANSI_YELLOW + "No statistics recorded yet." + MkPro.ANSI_RESET);
            return;
        }

        // 1. Resolve currentSessionId robustly
        String currentSessionId = null;
        if (context.getCentralMemory() != null && context.getCentralMemory().getCurrentSessionId() != null) {
            currentSessionId = context.getCentralMemory().getCurrentSessionId();
        }
        if (currentSessionId == null && context.getCurrentSession() != null) {
            currentSessionId = context.getCurrentSession().id();
        }
        if (currentSessionId == null) {
            currentSessionId = "default";
        }

        // 2. Session Filtering with Smart Fallback
        final String activeId = currentSessionId;
        List<AgentStat> sessionStats = allStats.stream()
                .filter(s -> s.getSessionId() != null && s.getSessionId().equalsIgnoreCase(activeId))
                .toList();

        if (sessionStats.isEmpty() && !allStats.isEmpty()) {
            String latestSessionId = allStats.get(allStats.size() - 1).getSessionId();
            if (latestSessionId != null) {
                sessionStats = allStats.stream()
                        .filter(s -> s.getSessionId() != null && s.getSessionId().equalsIgnoreCase(latestSessionId))
                        .toList();
                currentSessionId = latestSessionId;
            }
        }

        // 3. Format Output
        out.println(MkPro.ANSI_CYAN + "\n📊 CURRENT ONGOING SESSION STATS" + MkPro.ANSI_RESET);
        out.println("Session ID: " + MkPro.ANSI_YELLOW + currentSessionId + MkPro.ANSI_RESET);
        if (sessionStats.isEmpty()) {
            out.println("No activity recorded for this session.");
        } else {
            printStatsSection(out, sessionStats, false);
        }

        out.println(MkPro.ANSI_CYAN + "\n📈 TOTAL SESSIONS (ALL-TIME / LIFETIME)" + MkPro.ANSI_RESET);
        printStatsSection(out, allStats, true);
        
        out.flush();
    }

    private void printStatsSection(PrintWriter out, List<AgentStat> stats, boolean allTime) {
        long totalTokens = stats.stream().mapToLong(AgentStat::getTotalTokens).sum();
        out.println("Total Tokens: " + MkPro.ANSI_BRIGHT_GREEN + String.format("%,d", totalTokens) + MkPro.ANSI_RESET);
        
        // Example visualization
        String bar = "[████████░░░░]";
        out.println("Activity Density: " + bar);

        out.println(MkPro.ANSI_YELLOW + "Breakdown by Agent:" + MkPro.ANSI_RESET);
        stats.stream()
            .collect(Collectors.groupingBy(AgentStat::getAgentName, Collectors.summingLong(AgentStat::getTotalTokens)))
            .entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(e -> out.printf(" - %-15s: %s tokens\n", e.getKey(), String.format("%,d", e.getValue())));
    }

    @Override
    public String getName() { return "stats"; }
    @Override
    public String getDescription() { return "Show token usage and execution statistics"; }
}
