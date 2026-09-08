package com.mkpro.knowledge;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ADK tool: request_knowledge
 * 
 * Allows agents to signal knowledge gaps to the Knowledge Scheduler.
 * When an agent encounters a topic it lacks information about, it calls this tool
 * to request that the scheduler fetches and accumulates knowledge on it.
 * 
 * Anti-circular-dependency: refuses requests when running inside the scheduler's
 * analyze callback (isSchedulerContext flag).
 * 
 * Spam prevention: max 3 requests per session.
 * Dedup: won't create topics that already exist.
 */
public class RequestKnowledgeTool {

    private static volatile KnowledgeScheduler scheduler;
    private static volatile KnowledgeStore store;
    private static final AtomicInteger sessionRequestCount = new AtomicInteger(0);
    private static final int MAX_REQUESTS_PER_SESSION = 3;

    /** Thread-local flag to prevent circular requests from scheduler context */
    private static final ThreadLocal<Boolean> schedulerContext = ThreadLocal.withInitial(() -> false);

    /**
     * Initialize with scheduler and store references.
     */
    public static void init(KnowledgeScheduler knowledgeScheduler, KnowledgeStore knowledgeStore) {
        scheduler = knowledgeScheduler;
        store = knowledgeStore;
    }

    /**
     * Mark the current thread as running in scheduler context.
     * Call this before invoking the analyze callback.
     */
    public static void enterSchedulerContext() {
        schedulerContext.set(true);
    }

    /**
     * Clear the scheduler context flag.
     * Call this after the analyze callback completes.
     */
    public static void exitSchedulerContext() {
        schedulerContext.set(false);
    }

    /**
     * Check if currently in scheduler context.
     */
    public static boolean isInSchedulerContext() {
        return schedulerContext.get();
    }

    /**
     * Reset session counter (call on new session start).
     */
    public static void resetSessionCount() {
        sessionRequestCount.set(0);
    }

    /**
     * Get count of requests made this session.
     */
    public static int getSessionRequestCount() {
        return sessionRequestCount.get();
    }

    /**
     * Create the request_knowledge BaseTool.
     */
    public static BaseTool create() {
        return new BaseTool("request_knowledge",
            "Request the Knowledge Scheduler to accumulate information on a topic you lack knowledge about. " +
            "Use this when you encounter a question or task where you need more context that could be fetched " +
            "from documentation, APIs, or web sources. The scheduler will fetch, analyze, and store the knowledge " +
            "for future use. Max 3 requests per session.") {

            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                    .name(name())
                    .description(description())
                    .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(Map.of(
                            "topic", Schema.builder().type("STRING")
                                .description("Short topic name (lowercase, hyphenated, e.g. 'istio-mtls', 'spring-security-oauth2')").build(),
                            "description", Schema.builder().type("STRING")
                                .description("What you need to know about this topic. Be specific about the gap.").build(),
                            "sources", Schema.builder().type("STRING")
                                .description("Optional: comma-separated URLs that might contain the needed information.").build()
                        ))
                        .required(List.of("topic", "description"))
                        .build())
                    .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    // 1. Circular dependency check
                    if (isInSchedulerContext()) {
                        return Map.of(
                            "status", (Object) "REJECTED",
                            "reason", (Object) "Cannot request knowledge from within scheduler analysis context (circular dependency prevention)."
                        );
                    }

                    // 2. Spam prevention
                    if (sessionRequestCount.get() >= MAX_REQUESTS_PER_SESSION) {
                        return Map.of(
                            "status", (Object) "REJECTED",
                            "reason", (Object) ("Maximum " + MAX_REQUESTS_PER_SESSION + " knowledge requests per session reached.")
                        );
                    }

                    // 3. Validate inputs
                    String topic = args.get("topic") != null ? args.get("topic").toString().trim().toLowerCase() : "";
                    String description = args.get("description") != null ? args.get("description").toString().trim() : "";
                    String sourcesRaw = args.get("sources") != null ? args.get("sources").toString().trim() : "";

                    if (topic.isEmpty() || description.isEmpty()) {
                        return Map.of(
                            "status", (Object) "REJECTED",
                            "reason", (Object) "Both 'topic' and 'description' are required."
                        );
                    }

                    // Normalize topic name
                    topic = topic.replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-");

                    // 4. Dedup check — don't create if topic already exists
                    if (store != null) {
                        TopicReport existing = store.getReport(topic);
                        if (existing != null && existing.getSummary() != null && !existing.getSummary().isBlank()) {
                            return Map.of(
                                "status", (Object) "EXISTS",
                                "reason", (Object) "Topic '" + topic + "' already exists with accumulated knowledge. Use the existing report.",
                                "summary_preview", (Object) existing.getSummary().substring(0, Math.min(200, existing.getSummary().length()))
                            );
                        }
                    }

                    // 5. Parse sources
                    List<String> sources = new ArrayList<>();
                    if (!sourcesRaw.isEmpty()) {
                        for (String s : sourcesRaw.split("[,;\\s]+")) {
                            s = s.trim();
                            if (s.startsWith("http://") || s.startsWith("https://")) {
                                sources.add(s);
                            }
                        }
                    }

                    // 6. Create the topic via scheduler (auto-approved, priority boost)
                    if (scheduler != null) {
                        scheduler.addAgentRequestedTopic(topic, description, sources);
                        sessionRequestCount.incrementAndGet();

                        System.out.println("\u001b[36m[Knowledge] Agent requested topic: " + topic + " — auto-approved, will refresh shortly.\u001b[0m");

                        return Map.of(
                            "status", (Object) "ACCEPTED",
                            "topic", (Object) topic,
                            "message", (Object) "Knowledge request accepted. The scheduler will fetch and analyze '" + topic + "' shortly. " +
                                "The accumulated knowledge will be available for future queries."
                        );
                    } else {
                        return Map.of(
                            "status", (Object) "UNAVAILABLE",
                            "reason", (Object) "Knowledge scheduler is not active. Start with --scheduler flag."
                        );
                    }
                });
            }
        };
    }
}
