package com.mkpro.graph.viz;

import com.mkpro.graph.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Hybrid search engine that provides CamelCase/snake_case tokenization,
 * Jaccard token overlap similarity, exact matching boosts, and substring contains fallbacks.
 */
public class HybridSearcher {
    private final List<Entity> allEntities;
    private final Map<Entity, Set<String>> entityTokensMap;

    public HybridSearcher(List<Entity> entities) {
        this.allEntities = entities != null ? entities : Collections.emptyList();
        this.entityTokensMap = new HashMap<>();
        for (Entity entity : allEntities) {
            Set<String> tokens = new HashSet<>(tokenize(entity.name()));
            tokens.addAll(tokenize(entity.id()));
            tokens.addAll(tokenize(entity.type().name()));
            
            // Index metadata values as searchable tokens
            if (entity.metadata() != null) {
                for (Object value : entity.metadata().values()) {
                    if (value != null) {
                        tokens.addAll(tokenize(value.toString()));
                    }
                }
            }
            entityTokensMap.put(entity, tokens);
        }
    }

    /**
     * Searches entities and returns them ranked by relevance score (highest first).
     */
    public List<Entity> search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return allEntities;
        }

        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return allEntities;
        }

        // Map to hold match scores
        Map<Entity, Double> scores = new HashMap<>();

        for (Entity entity : allEntities) {
            Set<String> tokens = entityTokensMap.get(entity);
            if (tokens != null) {
                // 1. Calculate Jaccard similarity based on token overlap
                long intersectionCount = queryTokens.stream()
                        .filter(tokens::contains)
                        .count();
                
                if (intersectionCount > 0) {
                    double score = (double) intersectionCount / (tokens.size() + queryTokens.size() - intersectionCount);
                    
                    // Boost if query matches part of the entity name exactly
                    String lowercaseName = entity.name().toLowerCase();
                    String lowercaseQuery = query.trim().toLowerCase();
                    if (lowercaseName.equals(lowercaseQuery)) {
                        score += 1.5; // Exact name match boost
                    } else if (lowercaseName.contains(lowercaseQuery)) {
                        score += 0.5; // Partial name match boost
                    }
                    scores.put(entity, score);
                }
            }
        }

        // 2. Hybrid Fallback: If no exact token matches are found, do basic substring matching
        if (scores.isEmpty()) {
            String lowercaseQuery = query.toLowerCase().trim();
            for (Entity entity : allEntities) {
                if (entity.name().toLowerCase().contains(lowercaseQuery) ||
                    entity.id().toLowerCase().contains(lowercaseQuery)) {
                    scores.put(entity, 0.1); // Small flat score for fallback matches
                }
            }
        }

        // Sort by score descending
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Entity, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * Tokenizes identifiers by splitting on non-alphanumeric chars and CamelCase boundaries.
     */
    public static List<String> tokenize(String input) {
        if (input == null || input.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(input.split("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|[^a-zA-Z0-9]+"))
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
