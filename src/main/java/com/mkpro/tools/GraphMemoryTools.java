package com.mkpro.tools;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Single;
import com.mkpro.graph.*;
import com.mkpro.graph.viz.GraphVisualizerApp;
import com.mkpro.utils.PathUtils;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class GraphMemoryTools {

    private static final String MEMORY_KEY = "global_memory";

    private static String getDbPath() {
        return PathUtils.getBaseDocumentsPath().resolve("memory_graph.db").toString();
    }

    // Shared repository instance — avoids MapDB file locking conflicts
    private static volatile com.mkpro.graph.MapDbGraphRepository sharedRepository;

    private static synchronized com.mkpro.graph.MapDbGraphRepository getRepository() {
        if (sharedRepository == null) {
            sharedRepository = new com.mkpro.graph.MapDbGraphRepository(getDbPath());
        }
        return sharedRepository;
    }

    public static BaseTool memorizeFact() {
        return new BaseTool("memorize_fact", "Stores a fact in the knowledge graph.") {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "fact", Schema.builder().type("STRING").description("The fact to memorize.").build()
                                ))
                                .required(ImmutableList.of("fact"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String fact = (String) args.get("fact");
                    MapDbGraphRepository repository = getRepository();
                    ExtractionResult current = repository.loadExtraction(MEMORY_KEY).orElse(new ExtractionResult(new ArrayList<>(), new ArrayList<>()));
                    
                    List<Entity> entities = new ArrayList<>(current.entities());
                    List<Relationship> relationships = new ArrayList<>(current.relationships());
                    
                    String entityId = UUID.randomUUID().toString();
                    entities.add(new Entity(entityId, fact, EntityType.UNSPECIFIED, Map.of()));
                    
                    repository.saveExtraction(MEMORY_KEY, new ExtractionResult(entities, relationships));
                    return Collections.singletonMap("result", "Fact memorized successfully.");
                });
            }
        };
    }

    public static BaseTool recallMemory() {
        return new BaseTool("recall_memory", "Recalls facts from the knowledge graph based on a query.") {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder()
                                .type("OBJECT")
                                .properties(ImmutableMap.of(
                                        "query", Schema.builder().type("STRING").description("Search query.").build()
                                ))
                                .required(ImmutableList.of("query"))
                                .build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    String query = ((String) args.get("query")).toLowerCase();
                    MapDbGraphRepository repository = getRepository();
                    Optional<ExtractionResult> optResult = repository.loadExtraction(MEMORY_KEY);
                    if (optResult.isEmpty()) {
                        return Collections.singletonMap("result", "No memories found.");
                    }
                    
                    List<String> matches = optResult.get().entities().stream()
                            .map(Entity::name)
                            .filter(name -> name.toLowerCase().contains(query))
                            .collect(Collectors.toList());
                    
                    if (matches.isEmpty()) {
                        return Collections.singletonMap("result", "No matching memories found.");
                    }
                    
                    return Collections.singletonMap("result", "Recalled memories:\n- " + String.join("\n- ", matches));
                });
            }
        };
    }

    public static BaseTool visualizeGraph() {
        return new BaseTool("visualize_graph", "Opens a window to visualize the knowledge graph.") {
            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(Schema.builder().type("OBJECT").properties(Map.of()).build())
                        .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    new Thread(() -> {
                        GraphVisualizerApp.main(new String[]{getDbPath(), MEMORY_KEY});
                    }).start();
                    return Collections.singletonMap("result", "Visualization window opened.");
                });
            }
        };
    }
}
