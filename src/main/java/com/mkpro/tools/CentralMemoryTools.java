package com.mkpro.tools;

import static com.mkpro.ui.AnsiColors.*;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Single;
import com.mkpro.CentralMemory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * CentralMemoryTools provides agents with the ability to autonomously commit
 * important information to CentralMemory for persistence across sessions.
 *
 * Unlike graph_memory (which stores in a knowledge graph), these tools write to
 * the project-scoped CentralMemory store — the same one used by /remember.
 * This means committed data will be available in future sessions as project context.
 *
 * Intended for: Coordinator, GoalTracker, Architect
 */
public class CentralMemoryTools {

    /**
     * Tool that lets agents commit important insights, decisions, or context
     * to CentralMemory. Data persists across sessions and is loaded as project context.
     */
    public static BaseTool commitToMemory() {
        return new BaseTool(
            "commit_to_memory",
            "Persists important information to project memory for future sessions. " +
            "Use this when you discover architectural decisions, key patterns, user preferences, " +
            "project conventions, or critical context that should be remembered across sessions. " +
            "This is NOT for temporary notes — only for durable insights that future sessions will benefit from."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                    .name(name())
                    .description(description())
                    .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(ImmutableMap.of(
                            "key", Schema.builder()
                                .type("STRING")
                                .description("A short label for this memory (e.g., 'architecture', 'conventions', 'user_prefs', 'tech_stack', 'decisions')")
                                .build(),
                            "content", Schema.builder()
                                .type("STRING")
                                .description("The information to remember. Be concise but complete. Include context about WHY this matters.")
                                .build()
                        ))
                        .required(ImmutableList.of("key", "content"))
                        .build())
                    .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String key = (String) args.get("key");
                    String content = (String) args.get("content");
                    
                    String projectPath = System.getProperty("user.dir");
                    CentralMemory memory = CentralMemory.getInstance();
                    
                    // Append to existing memory rather than overwriting
                    String existing = memory.getMemory(projectPath);
                    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                    
                    String newEntry = "\n\n[" + key.toUpperCase() + " | " + timestamp + "]\n" + content;
                    String updated = (existing != null && !existing.isEmpty()) 
                        ? existing + newEntry 
                        : newEntry.trim();
                    
                    memory.saveMemory(projectPath, updated);
                    
                    System.out.println(ANSI_BLUE + "[Memory] Committed: " + key + ANSI_RESET);
                    
                    return Collections.singletonMap("result", 
                        "Successfully committed to project memory under key '" + key + "'. " +
                        "This will be available in future sessions.");
                });
            }
        };
    }

    /**
     * Tool that lets agents recall what's been previously committed to project memory.
     */
    public static BaseTool recallProjectMemory() {
        return new BaseTool(
            "recall_project_memory",
            "Retrieves previously saved project memory. Returns all committed insights, " +
            "decisions, and context for the current project. Use this at the start of complex tasks " +
            "to recall past decisions and conventions."
        ) {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                    .name(name())
                    .description(description())
                    .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(ImmutableMap.of(
                            "filter", Schema.builder()
                                .type("STRING")
                                .description("Optional keyword to filter memories (e.g., 'architecture', 'conventions'). Leave empty to get all.")
                                .build()
                        ))
                        .build())
                    .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String filter = args.get("filter") != null ? (String) args.get("filter") : "";
                    String projectPath = System.getProperty("user.dir");
                    CentralMemory memory = CentralMemory.getInstance();
                    
                    String stored = memory.getMemory(projectPath);
                    
                    if (stored == null || stored.isEmpty()) {
                        return Collections.singletonMap("result", 
                            "No project memory found. Use commit_to_memory to store important context.");
                    }
                    
                    // If filter is provided, only return matching sections
                    if (filter != null && !filter.isEmpty()) {
                        StringBuilder filtered = new StringBuilder();
                        String[] sections = stored.split("\\[");
                        for (String section : sections) {
                            if (section.toLowerCase().contains(filter.toLowerCase())) {
                                filtered.append("[").append(section);
                            }
                        }
                        if (filtered.length() == 0) {
                            return Collections.singletonMap("result", 
                                "No memories matching '" + filter + "'. Available memory:\n" + stored);
                        }
                        return Collections.singletonMap("result", filtered.toString().trim());
                    }
                    
                    return Collections.singletonMap("result", stored);
                });
            }
        };
    }
}
