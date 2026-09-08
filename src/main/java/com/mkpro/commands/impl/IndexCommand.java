package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.utils.IndexingHelper;

public class IndexCommand implements Command {
    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        IndexingHelper.indexProject(context);
        System.out.println("Project indexing completed.");

        // Also scan for project facts (constants, dependencies, config constraints)
        if (context.getFactEngine() != null) {
            com.mkpro.facts.ProjectFactScanner scanner = new com.mkpro.facts.ProjectFactScanner(context.getFactEngine());
            int facts = scanner.scan(java.nio.file.Paths.get(System.getProperty("user.dir")));
            if (facts > 0) {
                System.out.println("\u001b[32m  Discovered " + facts + " project fact(s) (constants, dependencies, constraints)\u001b[0m");
            }
        }

        // Deep mode: agent-assisted fact discovery
        boolean deep = args.length > 0 && ("--deep".equals(args[0]) || "deep".equals(args[0]));
        if (deep && context.getFactEngine() != null && context.getRunner() != null && context.getCurrentSession() != null) {
            final MkProContext ctx = context;
            java.util.function.Function<String, String> llmCallback = prompt -> {
                try {
                    com.google.genai.types.Content msg = com.google.genai.types.Content.fromParts(
                        new com.google.genai.types.Part[]{com.google.genai.types.Part.fromText(prompt)});
                    StringBuilder resp = new StringBuilder();
                    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    ctx.getRunner().runAsync(ctx.getCurrentSession().sessionKey(), msg)
                        .blockingSubscribe(
                            event -> event.content().ifPresent(c -> c.parts().ifPresent(parts -> {
                                for (com.google.genai.types.Part part : parts) {
                                    part.text().ifPresent(resp::append);
                                }
                            })),
                            error -> latch.countDown(),
                            latch::countDown
                        );
                    latch.await(60, java.util.concurrent.TimeUnit.SECONDS);
                    return resp.toString().trim().isEmpty() ? null : resp.toString().trim();
                } catch (Exception e) { return null; }
            };

            com.mkpro.facts.AgentFactDiscovery discovery = new com.mkpro.facts.AgentFactDiscovery(
                context.getFactEngine(), llmCallback);
            int deepFacts = discovery.analyze(java.nio.file.Paths.get(System.getProperty("user.dir")));
            if (deepFacts > 0) {
                System.out.println("\u001b[32m  Deep discovery: " + deepFacts + " fact(s) from agent analysis\u001b[0m");
            }
        } else if (deep && context.getRunner() == null) {
            System.out.println("\u001b[33m  --deep requires an active LLM runner.\u001b[0m");
        }

        // Persist discovered facts to MapDB
        if (context.getFactEngine() != null) {
            context.getFactEngine().persistProjectFacts();
        }
    }

    @Override
    public String getName() {
        return "index";
    }

    @Override
    public String getDescription() {
        return "Index project for search + fact discovery. Usage: /index [--deep]";
    }
}
