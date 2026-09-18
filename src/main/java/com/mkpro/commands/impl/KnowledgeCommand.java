package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.knowledge.KnowledgeScheduler;
import com.mkpro.knowledge.KnowledgeStore;
import com.mkpro.knowledge.TopicConfig;
import com.mkpro.knowledge.TopicIndex;
import com.mkpro.knowledge.TopicReport;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

/**
 * /know command - search accumulated knowledge or manage topics.
 * 
 * Usage:
 *   /know <query>         - search all topics by TF-IDF similarity
 *   /know topics          - list all known topics with summaries
 *   /know topic <name>    - show full report for a specific topic
 *   /know status          - show scheduler status (last refresh times)
 *   /know refresh <name>  - force refresh a specific topic
 *   /know refresh all     - force refresh all topics
 */
public class KnowledgeCommand implements Command {

    @Override
    public String getName() {
        return "know";
    }

    @Override
    public String getDescription() {
        return "Search accumulated knowledge or manage knowledge topics.";
    }

    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        PrintWriter out = context.getTerminal().writer();

        KnowledgeStore store = context.getKnowledgeStore();
        TopicIndex index = context.getTopicIndex();
        KnowledgeScheduler scheduler = context.getKnowledgeScheduler();

        if (store == null || index == null) {
            out.println("\u001b[33m[Knowledge] Knowledge scheduler is not active. Start with --scheduler flag.\u001b[0m");
            out.flush();
            return;
        }

        if (args.length == 0) {
            printUsage(out);
            return;
        }

        String subcommand = args[0].toLowerCase();

        switch (subcommand) {
            case "topics" -> listTopics(out, store);
            case "topic" -> {
                if (args.length < 2) {
                    out.println("\u001b[33mUsage: /know topic <name>\u001b[0m");
                } else {
                    showTopic(out, store, args[1]);
                }
            }
            case "status" -> showStatus(out, scheduler);
            case "refresh" -> {
                if (args.length < 2) {
                    out.println("\u001b[33mUsage: /know refresh <name|all>\u001b[0m");
                } else {
                    forceRefresh(out, scheduler, args[1]);
                }
            }
            case "approve" -> {
                if (scheduler == null || args.length < 2) {
                    out.println("\u001b[33mUsage: /know approve <topic-name>\u001b[0m");
                } else {
                    scheduler.approveDiscovery(args[1]);
                    out.println("\u001b[32mApproved topic: " + args[1] + " — will refresh shortly.\u001b[0m");
                }
            }
            case "dismiss" -> {
                if (scheduler == null || args.length < 2) {
                    out.println("\u001b[33mUsage: /know dismiss <topic-name>\u001b[0m");
                } else {
                    scheduler.dismissDiscovery(args[1]);
                    out.println("\u001b[32mDismissed topic: " + args[1] + "\u001b[0m");
                }
            }
            case "add" -> {
                if (scheduler == null) {
                    out.println("\u001b[33mScheduler not active. Start with --scheduler flag.\u001b[0m");
                } else if (args.length < 3) {
                    out.println("\u001b[33mUsage: /know add <name> <url> [instruction]\u001b[0m");
                } else {
                    TopicConfig newTopic = new TopicConfig();
                    newTopic.setName(args[1]);
                    newTopic.setTitle(args[1]);
                    newTopic.setSources(java.util.List.of(args[2]));
                    if (args.length > 3) {
                        newTopic.setInstruction(String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)));
                    }
                    newTopic.setRefreshIntervalMinutes(60);
                    boolean created = scheduler.addTopic(newTopic);
                    if (created) {
                        out.println("\u001b[32m✓ Topic '" + args[1] + "' created. First refresh in 30s.\u001b[0m");
                    } else {
                        out.println("\u001b[33mTopic '" + args[1] + "' already exists.\u001b[0m");
                    }
                }
            }
            case "remove" -> {
                if (scheduler == null) {
                    out.println("\u001b[33mScheduler not active.\u001b[0m");
                } else if (args.length < 2) {
                    out.println("\u001b[33mUsage: /know remove <name>\u001b[0m");
                } else {
                    boolean removed = scheduler.removeTopic(args[1]);
                    if (removed) {
                        out.println("\u001b[32m✓ Topic '" + args[1] + "' removed.\u001b[0m");
                    } else {
                        out.println("\u001b[33mTopic '" + args[1] + "' not found.\u001b[0m");
                    }
                }
            }
            default -> {
                // Treat everything else as a search query
                String query = String.join(" ", args);
                search(out, index, store, query, scheduler);
            }
        }

        out.flush();
    }

    private void search(PrintWriter out, TopicIndex index, KnowledgeStore store, String query, KnowledgeScheduler scheduler) {
        List<TopicIndex.SearchResult> results = index.search(query, 5);

        if (results.isEmpty()) {
            out.println("\u001b[33mNo results found for: \"" + query + "\"\u001b[0m");
            out.println("\u001b[90mTip: Use /know topics to see available topics.\u001b[0m");
            return;
        }

        // Record access for all matched topics (access-frequency refresh priority)
        if (scheduler != null) {
            for (TopicIndex.SearchResult r : results) {
                scheduler.recordAccess(r.getTopicName());
            }
        }

        out.println("\u001b[36m╔══ Knowledge Search: \"" + query + "\" ══╗\u001b[0m");
        out.println();

        for (int i = 0; i < results.size(); i++) {
            TopicIndex.SearchResult result = results.get(i);
            double pct = result.getScore() * 100;

            out.printf("\u001b[32m  %d. %s\u001b[0m \u001b[90m(%.1f%% match)\u001b[0m%n", 
                i + 1, result.getTopicName(), pct);

            // Show snippet
            String snippet = result.getSnippet();
            if (snippet != null && !snippet.isBlank()) {
                // Wrap at 80 chars
                String wrapped = wrapText(snippet, 80, "     ");
                out.println("\u001b[37m" + wrapped + "\u001b[0m");
            }

            // Show last updated from report
            TopicReport report = store.getReport(result.getTopicName());
            if (report != null && report.getLastUpdated() != null) {
                out.println("\u001b[90m     Last updated: " + report.getLastUpdated() + "\u001b[0m");
            }
            out.println();
        }

        // Also show relationship facts from FactEngine if available
        com.mkpro.facts.FactEngine factEngine = null;
        try {
            // Access via reflection-free path if context is available
            factEngine = com.mkpro.facts.VerifyFactTool.getEngine();
        } catch (Exception e) { /* ignore */ }

        if (factEngine != null) {
            List<String> rels = factEngine.queryRelationships(query);
            if (!rels.isEmpty()) {
                out.println("\u001b[36m  ── Verified Relationships ──\u001b[0m");
                for (String rel : rels) {
                    out.println("\u001b[90m    • " + rel + "\u001b[0m");
                }
                out.println();
            }
        }
    }

    private void listTopics(PrintWriter out, KnowledgeStore store) {
        List<TopicReport> reports = store.getAllReports();

        if (reports.isEmpty()) {
            out.println("\u001b[33mNo knowledge topics yet. Configure schedules.yaml and start with --scheduler.\u001b[0m");
            return;
        }

        out.println("\u001b[36m╔══ Knowledge Topics (" + reports.size() + ") ══╗\u001b[0m");
        out.println();

        for (TopicReport report : reports) {
            out.printf("\u001b[32m  • %s\u001b[0m", report.getName());
            if (report.getTitle() != null && !report.getTitle().equals(report.getName())) {
                out.printf(" — %s", report.getTitle());
            }
            out.println();

            if (report.getLastUpdated() != null) {
                out.printf("\u001b[90m    Updated: %s | Confidence: %.0f%% | Keywords: %s\u001b[0m%n",
                    report.getLastUpdated(),
                    report.getConfidence() * 100,
                    report.getKeywords() != null ? String.join(", ", report.getKeywords().subList(0, Math.min(5, report.getKeywords().size()))) : "none");
            }

            // First line of summary
            if (report.getSummary() != null && !report.getSummary().isBlank()) {
                String firstLine = report.getSummary().split("\n")[0];
                if (firstLine.length() > 100) firstLine = firstLine.substring(0, 100) + "...";
                out.println("\u001b[37m    " + firstLine + "\u001b[0m");
            }
            out.println();
        }
    }

    private void showTopic(PrintWriter out, KnowledgeStore store, String topicName) {
        TopicReport report = store.getReport(topicName);
        if (report == null) {
            out.println("\u001b[33mTopic not found: " + topicName + "\u001b[0m");
            return;
        }

        out.println("\u001b[36m╔══ Topic: " + report.getName() + " ══╗\u001b[0m");
        if (report.getTitle() != null) {
            out.println("\u001b[37m  Title: " + report.getTitle() + "\u001b[0m");
        }
        out.printf("\u001b[90m  Updated: %s | Confidence: %.0f%%\u001b[0m%n",
            report.getLastUpdated() != null ? report.getLastUpdated() : "never",
            report.getConfidence() * 100);

        if (report.getSources() != null && !report.getSources().isEmpty()) {
            out.println("\u001b[90m  Sources: " + String.join(", ", report.getSources()) + "\u001b[0m");
        }

        if (report.getKeywords() != null && !report.getKeywords().isEmpty()) {
            out.println("\u001b[90m  Keywords: " + String.join(", ", report.getKeywords()) + "\u001b[0m");
        }

        out.println();
        out.println("\u001b[37m" + (report.getSummary() != null ? report.getSummary() : "(no summary yet)") + "\u001b[0m");

        // History
        if (report.getHistory() != null && !report.getHistory().isEmpty()) {
            out.println();
            out.println("\u001b[90m  --- History (" + report.getHistory().size() + " updates) ---\u001b[0m");
            int show = Math.min(5, report.getHistory().size());
            for (int i = report.getHistory().size() - show; i < report.getHistory().size(); i++) {
                TopicReport.HistoryEntry entry = report.getHistory().get(i);
                out.printf("\u001b[90m  [%s] %s\u001b[0m%n", entry.getDate(), entry.getDelta());
            }
        }
    }

    private void showStatus(PrintWriter out, KnowledgeScheduler scheduler) {
        if (scheduler == null) {
            out.println("\u001b[33mScheduler is not running.\u001b[0m");
            return;
        }

        Map<String, String> status = scheduler.getStatus();
        if (status.isEmpty()) {
            out.println("\u001b[33mNo topics configured.\u001b[0m");
            return;
        }

        out.println("\u001b[36m╔══ Knowledge Scheduler Status ══╗\u001b[0m");
        out.println();

        Map<String, Integer> accessFreqs = scheduler.getAccessFrequencies();
        for (Map.Entry<String, String> entry : status.entrySet()) {
            String icon = "never".equals(entry.getValue()) ? "⏳" : "✓";
            int accesses = accessFreqs.getOrDefault(entry.getKey(), 0);
            String accessStr = accesses > 0 ? " (" + accesses + " searches)" : "";
            out.printf("  %s \u001b[32m%s\u001b[0m → %s%s%n", icon, entry.getKey(), entry.getValue(), accessStr);
        }

        // Show pending discoveries
        var discoveries = scheduler.getPendingDiscoveries();
        if (!discoveries.isEmpty()) {
            out.println();
            out.println("\u001b[33m  ⚡ Pending Discoveries (" + discoveries.size() + "):\u001b[0m");
            for (var d : discoveries) {
                out.printf("\u001b[90m    • %s — %s (from: %s)\u001b[0m%n", d.name, d.description, d.discoveredFrom);
            }
            out.println("\u001b[90m    Use /know approve <name> or /know dismiss <name>\u001b[0m");
        }
    }

    private void forceRefresh(PrintWriter out, KnowledgeScheduler scheduler, String name) {
        if (scheduler == null) {
            out.println("\u001b[33mScheduler is not running.\u001b[0m");
            return;
        }

        if ("all".equalsIgnoreCase(name)) {
            Map<String, String> status = scheduler.getStatus();
            for (String topicName : status.keySet()) {
                scheduler.forceRefresh(topicName);
            }
            out.println("\u001b[32mForce refresh triggered for all " + status.size() + " topics.\u001b[0m");
        } else {
            scheduler.forceRefresh(name);
            out.println("\u001b[32mForce refresh triggered for: " + name + "\u001b[0m");
        }
    }

    private void printUsage(PrintWriter out) {
        out.println("\u001b[36mUsage:\u001b[0m");
        out.println("  /know <query>         Search all topics by relevance");
        out.println("  /know topics          List all known topics");
        out.println("  /know topic <name>    Show full report for a topic");
        out.println("  /know status          Show scheduler status");
        out.println("  /know refresh <name>  Force refresh a topic (or 'all')");
    }

    private String wrapText(String text, int width, String indent) {
        StringBuilder sb = new StringBuilder();
        String[] words = text.split("\\s+");
        int lineLen = 0;
        sb.append(indent);
        for (String word : words) {
            if (lineLen + word.length() + 1 > width) {
                sb.append("\n").append(indent);
                lineLen = 0;
            }
            if (lineLen > 0) {
                sb.append(" ");
                lineLen++;
            }
            sb.append(word);
            lineLen += word.length();
        }
        return sb.toString();
    }
}
