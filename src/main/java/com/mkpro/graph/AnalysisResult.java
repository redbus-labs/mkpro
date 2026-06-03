package com.mkpro.graph;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record AnalysisResult(
    Map<Entity, Double> centralityScores,
    Map<Integer, List<Entity>> communities,
    Map<Entity, Set<Entity>> blastRadius,
    Set<Entity> deadCode,
    List<Set<Entity>> cycles
) {
}
