package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.knowledge.*;

import java.util.Map;

/**
 * /scheduler command — toggle Knowledge Scheduler on/off during a session.
 *
 * Usage:
 *   /scheduler         - Show current status
 *   /scheduler on      - Start scheduler (loads schedules.yaml)
 *   /scheduler off     - Stop scheduler (keeps accumulated data)
 */
public class SchedulerCommand implements Command {

    @Override
    public String getName() {
        return "scheduler";
    }

    @Override
    public String getDescription() {
        return "Toggle Knowledge Scheduler. Usage: /scheduler [on|off]";
    }

    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        if (args.length == 0) {
            showStatus(context);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "on" -> startScheduler(context);
            case "off" -> stopScheduler(context);
            default -> showStatus(context);
        }
    }

    private void showStatus(MkProContext context) {
        KnowledgeScheduler scheduler = context.getKnowledgeScheduler();
        if (scheduler == null) {
            System.out.println("\u001b[33m[Scheduler] Knowledge Scheduler is OFF.\u001b[0m");
            System.out.println("  Use /scheduler on to start.");
            // Check if data exists even without scheduler
            if (context.getKnowledgeStore() != null) {
                int topics = context.getKnowledgeStore().getAllReports().size();
                if (topics > 0) {
                    System.out.println("  (" + topics + " topic report(s) available for /know search)");
                }
            }
        } else {
            System.out.println("\u001b[32m[Scheduler] Knowledge Scheduler is ON\u001b[0m");
            Map<String, String> status = scheduler.getStatus();
            System.out.println("  Topics: " + status.size());
            for (Map.Entry<String, String> entry : status.entrySet()) {
                String icon = "never".equals(entry.getValue()) ? "⏳" : "✓";
                System.out.println("    " + icon + " " + entry.getKey() + " → " + entry.getValue());
            }
            var discoveries = scheduler.getPendingDiscoveries();
            if (!discoveries.isEmpty()) {
                System.out.println("  Pending discoveries: " + discoveries.size());
            }
        }
    }

    private void startScheduler(MkProContext context) {
        if (context.getKnowledgeScheduler() != null) {
            System.out.println("\u001b[33m[Scheduler] Already running. Use /scheduler off first.\u001b[0m");
            return;
        }

        try {
            // Initialize store and index if not already present
            KnowledgeStore store = context.getKnowledgeStore();
            TopicIndex index = context.getTopicIndex();

            if (store == null) {
                store = new KnowledgeStore(context.getCentralMemory());
                context.setKnowledgeStore(store);
            }
            if (index == null) {
                index = new TopicIndex();
                context.setTopicIndex(index);
            }

            SourceFetcher fetcher = new SourceFetcher();

            // Load topics from schedules.yaml
            java.util.List<TopicConfig> topics = loadSchedulesConfig();

            KnowledgeScheduler scheduler = new KnowledgeScheduler(store, index, fetcher, topics);

            // Wire analyze callback with scheduler context protection
            final MkProContext ctx = context;
            scheduler.setAnalyzeCallback((topicName, prompt) -> {
                RequestKnowledgeTool.enterSchedulerContext();
                try {
                    if (ctx.getRunner() == null || ctx.getCurrentSession() == null) {
                        return prompt.length() > 2000 ? prompt.substring(0, 2000) : prompt;
                    }
                    // Run through ADK runner
                    com.google.genai.types.Content message = com.google.genai.types.Content.fromParts(
                        new com.google.genai.types.Part[]{com.google.genai.types.Part.fromText(prompt)});
                    StringBuilder responseText = new StringBuilder();
                    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    ctx.getRunner().runAsync(ctx.getCurrentSession().sessionKey(), message)
                        .blockingSubscribe(
                            event -> event.content().ifPresent(c -> c.parts().ifPresent(parts -> {
                                for (com.google.genai.types.Part part : parts) {
                                    part.text().ifPresent(responseText::append);
                                }
                            })),
                            error -> latch.countDown(),
                            latch::countDown
                        );
                    latch.await(120, java.util.concurrent.TimeUnit.SECONDS);
                    String result = responseText.toString().trim();
                    return result.isEmpty() ? null : result;
                } catch (Exception e) {
                    return null;
                } finally {
                    RequestKnowledgeTool.exitSchedulerContext();
                }
            });

            // Init RequestKnowledgeTool
            RequestKnowledgeTool.init(scheduler, store);

            // Wire FactExtractor if FactEngine is available
            if (context.getFactEngine() != null) {
                // Reuse the scheduler's analyze callback for fact extraction LLM calls
                final com.mkpro.core.MkProContext ctx3 = context;
                com.mkpro.facts.FactExtractor extractor = new com.mkpro.facts.FactExtractor(
                    context.getFactEngine(), prompt -> {
                        try {
                            if (ctx3.getRunner() == null || ctx3.getCurrentSession() == null) return null;
                            com.mkpro.knowledge.RequestKnowledgeTool.enterSchedulerContext();
                            try {
                                com.google.genai.types.Content msg = com.google.genai.types.Content.fromParts(
                                    new com.google.genai.types.Part[]{com.google.genai.types.Part.fromText(prompt)});
                                StringBuilder resp = new StringBuilder();
                                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                                ctx3.getRunner().runAsync(ctx3.getCurrentSession().sessionKey(), msg)
                                    .blockingSubscribe(
                                        event -> event.content().ifPresent(c -> c.parts().ifPresent(parts -> {
                                            for (com.google.genai.types.Part part : parts) {
                                                part.text().ifPresent(resp::append);
                                            }
                                        })),
                                        error -> latch.countDown(),
                                        latch::countDown
                                    );
                                latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
                                return resp.toString().trim().isEmpty() ? null : resp.toString().trim();
                            } finally {
                                com.mkpro.knowledge.RequestKnowledgeTool.exitSchedulerContext();
                            }
                        } catch (Exception e) { return null; }
                    });
                scheduler.setFactExtractor(extractor);
            }

            // Rebuild index from existing reports
            for (TopicReport report : store.getAllReports()) {
                if (report.getSummary() != null && !report.getSummary().isBlank()) {
                    index.indexTopic(report.getName(), report.getSummary());
                }
            }
            index.rebuildIdf();

            context.setKnowledgeScheduler(scheduler);

            // Wire MakerLoop for proactive knowledge gap detection
            if (context.getMakerLoop() != null) {
                context.getMakerLoop().setKnowledgeComponents(scheduler, store, index);
                // Wire LLM callback for knowledge suggestions
                final com.mkpro.core.MkProContext ctx2 = context;
                context.getMakerLoop().setLlmCallback(prompt -> {
                    try {
                        if (ctx2.getRunner() == null || ctx2.getCurrentSession() == null) return null;
                        com.mkpro.knowledge.RequestKnowledgeTool.enterSchedulerContext();
                        try {
                            com.google.genai.types.Content msg = com.google.genai.types.Content.fromParts(
                                new com.google.genai.types.Part[]{com.google.genai.types.Part.fromText(prompt)});
                            StringBuilder resp = new StringBuilder();
                            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                            ctx2.getRunner().runAsync(ctx2.getCurrentSession().sessionKey(), msg)
                                .blockingSubscribe(
                                    event -> event.content().ifPresent(c -> c.parts().ifPresent(parts -> {
                                        for (com.google.genai.types.Part part : parts) {
                                            part.text().ifPresent(resp::append);
                                        }
                                    })),
                                    error -> latch.countDown(),
                                    latch::countDown
                                );
                            latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
                            return resp.toString().trim().isEmpty() ? null : resp.toString().trim();
                        } finally {
                            com.mkpro.knowledge.RequestKnowledgeTool.exitSchedulerContext();
                        }
                    } catch (Exception e) { return null; }
                });
            }

            if (!topics.isEmpty()) {
                scheduler.start();
                System.out.println("\u001b[32m[Scheduler] Started with " + topics.size() + " topic(s).\u001b[0m");
            } else {
                System.out.println("\u001b[32m[Scheduler] Started (no topics configured in schedules.yaml).\u001b[0m");
                System.out.println("  Use /know add <name> <url> to add topics.");
            }

            // Remember preference for next session
            context.getCentralMemory().saveMemory("pref:scheduler", "on");

        } catch (Exception e) {
            System.out.println("\u001b[31m[Scheduler] Failed to start: " + e.getMessage() + "\u001b[0m");
        }
    }

    private void stopScheduler(MkProContext context) {
        KnowledgeScheduler scheduler = context.getKnowledgeScheduler();
        if (scheduler == null) {
            System.out.println("\u001b[33m[Scheduler] Not running.\u001b[0m");
            return;
        }

        scheduler.stop();
        context.setKnowledgeScheduler(null);
        System.out.println("\u001b[32m[Scheduler] Stopped. Accumulated data preserved (/know search still works).\u001b[0m");

        // Remember preference for next session
        context.getCentralMemory().saveMemory("pref:scheduler", "off");
    }

    /**
     * Load topics from schedules.yaml (same logic as BootstrapService).
     */
    private java.util.List<TopicConfig> loadSchedulesConfig() {
        java.util.List<TopicConfig> topics = new java.util.ArrayList<>();

        java.nio.file.Path[] searchPaths = {
            java.nio.file.Paths.get(".mkpro", "schedules.yaml"),
            java.nio.file.Paths.get(System.getProperty("user.home"), "Documents", "mkpro", "schedules.yaml")
        };

        java.nio.file.Path configPath = null;
        for (java.nio.file.Path p : searchPaths) {
            if (java.nio.file.Files.exists(p)) {
                configPath = p;
                break;
            }
        }

        if (configPath == null) return topics;

        try {
            com.fasterxml.jackson.databind.ObjectMapper yamlMapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
            com.fasterxml.jackson.databind.JsonNode root = yamlMapper.readTree(configPath.toFile());
            com.fasterxml.jackson.databind.JsonNode topicsNode = root.get("topics");

            if (topicsNode != null && topicsNode.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode node : topicsNode) {
                    TopicConfig tc = new TopicConfig();
                    tc.setName(node.has("name") ? node.get("name").asText() : null);
                    tc.setTitle(node.has("title") ? node.get("title").asText() : tc.getName());
                    if (node.has("sources") && node.get("sources").isArray()) {
                        java.util.List<String> sources = new java.util.ArrayList<>();
                        for (com.fasterxml.jackson.databind.JsonNode s : node.get("sources")) {
                            sources.add(s.asText());
                        }
                        tc.setSources(sources);
                    }
                    if (node.has("instruction")) tc.setInstruction(node.get("instruction").asText());
                    if (node.has("agent")) tc.setAgent(node.get("agent").asText());
                    if (node.has("refreshIntervalMinutes")) tc.setRefreshIntervalMinutes(node.get("refreshIntervalMinutes").asInt());

                    if (tc.getName() != null && tc.getSources() != null && !tc.getSources().isEmpty()) {
                        topics.add(tc);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("\u001b[33m[Scheduler] Error loading schedules.yaml: " + e.getMessage() + "\u001b[0m");
        }

        return topics;
    }
}
