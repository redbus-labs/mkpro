package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.models.AgentStat;
import com.mkpro.MkPro;
import java.util.*;
import java.util.stream.Collectors;

public class StatsCommand implements Command {
    @Override
    public void execute(String[] args, MkProContext context) {
        List<AgentStat> stats = context.getCentralMemory().getAgentStats();

        // Use System.out as fallback if terminal is unavailable
        java.io.PrintWriter out = context.getTerminal() != null 
            ? context.getTerminal().writer() 
            : new java.io.PrintWriter(System.out, true);

        if (stats == null || stats.isEmpty()) {
            out.println(MkPro.ANSI_YELLOW + "No statistics recorded yet." + MkPro.ANSI_RESET);
            return;
        }

        long totalTokens = stats.stream().mapToLong(AgentStat::getTotalTokens).sum();
        long totalSessions = stats.stream()
                .map(AgentStat::getSessionId)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        out.println(MkPro.ANSI_CYAN + "\n=== Agent & Token Statistics ===" + MkPro.ANSI_RESET);
        out.println("Total Sessions: " + MkPro.ANSI_BRIGHT_GREEN + totalSessions + MkPro.ANSI_RESET);
        out.println("Total Tokens:   " + MkPro.ANSI_BRIGHT_GREEN + String.format("%,d", totalTokens) + MkPro.ANSI_RESET);

        // Group by Agent
        out.println(MkPro.ANSI_YELLOW + "\nTokens per Agent:" + MkPro.ANSI_RESET);
        stats.stream()
            .collect(Collectors.groupingBy(AgentStat::getAgentName, Collectors.summingLong(AgentStat::getTotalTokens)))
            .entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(e -> out.printf(" - %-15s: %s tokens\n", e.getKey(), String.format("%,d", e.getValue())));

        // Group by Model
        out.println(MkPro.ANSI_YELLOW + "\nTokens per Model:" + MkPro.ANSI_RESET);
        stats.stream()
            .collect(Collectors.groupingBy(AgentStat::getModel, Collectors.summingLong(AgentStat::getTotalTokens)))
            .entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(e -> out.printf(" - %-15s: %s tokens\n", e.getKey(), String.format("%,d", e.getValue())));

        out.println(MkPro.ANSI_CYAN + "================================\n" + MkPro.ANSI_RESET);
        out.flush();
    }

    @Override
    public String getName() { return "stats"; }
    @Override
    public String getDescription() { return "Show token usage and execution statistics"; }
}