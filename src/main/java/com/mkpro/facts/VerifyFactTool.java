package com.mkpro.facts;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;

import java.util.*;

/**
 * Agent-callable tool for verifying mathematical facts and checking relationships.
 *
 * Usage by agents:
 *   verify_fact(action="verify_math", domain="circle_area", variables={"r": 5})
 *   verify_fact(action="check_relationship", subject="HPA", predicate="requires", object="metrics-server")
 *   verify_fact(action="query", subject="Spring Boot 3")
 */
public class VerifyFactTool {

    private static volatile FactEngine engine;

    /**
     * Initialize with a FactEngine instance.
     */
    public static void init(FactEngine factEngine) {
        engine = factEngine;
    }

    /**
     * Get the engine instance (used by /know command for unified query).
     */
    public static FactEngine getEngine() {
        return engine;
    }

    /**
     * Create the verify_fact BaseTool.
     */
    public static BaseTool create() {
        return new BaseTool("verify_fact",
            "Verify mathematical formulas, check technology relationships, and validate claims against known facts. " +
            "Actions: 'verify_math' (compute a formula), 'validate_math' (check a claimed result), " +
            "'check_relationship' (verify subject-predicate-object), 'query' (get all relationships for a subject). " +
            "Examples: verify_fact(action='verify_math', domain='circle_area', variables='{\"r\": 5}') → result: 78.54") {

            @Override
            public Optional<FunctionDeclaration> declaration() {
                return Optional.of(FunctionDeclaration.builder()
                    .name(name())
                    .description(description())
                    .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(Map.of(
                            "action", Schema.builder().type("STRING")
                                .description("Action: 'verify_math', 'validate_math', 'check_relationship', 'query'").build(),
                            "domain", Schema.builder().type("STRING")
                                .description("For math: fact key like 'circle_area', 'pythagorean', 'newton_second', 'throughput'. Or a keyword.").build(),
                            "variables", Schema.builder().type("STRING")
                                .description("JSON map of variables, e.g. '{\"r\": 5}' or '{\"m\": 10, \"a\": 9.8}'").build(),
                            "subject", Schema.builder().type("STRING")
                                .description("For relationships: the subject entity (e.g. 'HPA', 'Spring Boot 3')").build(),
                            "predicate", Schema.builder().type("STRING")
                                .description("For relationships: the predicate (e.g. 'requires', 'replaces', 'part_of')").build(),
                            "object", Schema.builder().type("STRING")
                                .description("For relationships: the object entity (e.g. 'metrics-server', 'Java 17+')").build()
                        ))
                        .required(List.of("action"))
                        .build())
                    .build());
            }

            @Override
            public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
                return Single.fromCallable(() -> {
                    if (engine == null) {
                        return Map.of("error", (Object) "FactEngine not initialized");
                    }

                    String action = (String) args.getOrDefault("action", "");

                    switch (action) {
                        case "verify_math": {
                            String domain = (String) args.getOrDefault("domain", "");
                            Map<String, Object> vars = parseVariables((String) args.getOrDefault("variables", "{}"));
                            Map<String, Object> result = engine.verifyMath(domain, vars);
                            result = new HashMap<>(result);
                            result.put("action", "verify_math");
                            result.put("domain", domain);
                            return result;
                        }

                        case "validate_math": {
                            String domain = (String) args.getOrDefault("domain", "");
                            Map<String, Object> vars = parseVariables((String) args.getOrDefault("variables", "{}"));
                            Map<String, Object> result = engine.validateMath(domain, vars);
                            result = new HashMap<>(result);
                            result.put("action", "validate_math");
                            return result;
                        }

                        case "check_relationship": {
                            String subject = (String) args.getOrDefault("subject", "");
                            String predicate = (String) args.getOrDefault("predicate", "");
                            String object = (String) args.getOrDefault("object", "");
                            Map<String, Object> result = engine.checkRelationship(subject, predicate, object);
                            return result;
                        }

                        case "query": {
                            String subject = (String) args.getOrDefault("subject", "");
                            List<String> rels = engine.queryRelationships(subject);
                            return Map.of("subject", (Object) subject, "relationships", rels,
                                         "count", rels.size());
                        }

                        default:
                            return Map.of("error", (Object) ("Unknown action: " + action +
                                ". Use: verify_math, validate_math, check_relationship, query"));
                    }
                });
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseVariables(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return new HashMap<>();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of("_parse_error", "Could not parse variables JSON: " + e.getMessage());
        }
    }
}
