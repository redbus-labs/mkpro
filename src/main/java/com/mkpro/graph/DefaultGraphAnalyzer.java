package com.mkpro.graph;

import org.jgrapht.Graph;
import org.jgrapht.alg.scoring.PageRank;
import org.jgrapht.alg.connectivity.KosarajuStrongConnectivityInspector;

import java.util.*;

public class DefaultGraphAnalyzer implements GraphAnalyzer {

    @Override
    public AnalysisResult analyze(Graph<Entity, RelationshipEdge> graph) {
        // 1. Calculate PageRank (Centrality)
        PageRank<Entity, RelationshipEdge> pageRank = new PageRank<>(graph);
        Map<Entity, Double> scores = pageRank.getScores();

        // 2. Calculate Blast Radius (Transitive Dependents)
        Map<Entity, Set<Entity>> blastRadius = calculateBlastRadius(graph);

        // 3. Find Unreachable / Dead Code
        Set<Entity> deadCode = findDeadCode(graph);

        // 4. Find Strongly Connected Components / Cycles
        List<Set<Entity>> cycles = findCycles(graph);

        return new AnalysisResult(scores, Collections.emptyMap(), blastRadius, deadCode, cycles);
    }

    private Map<Entity, Set<Entity>> calculateBlastRadius(Graph<Entity, RelationshipEdge> graph) {
        Map<Entity, Set<Entity>> result = new HashMap<>();
        for (Entity node : graph.vertexSet()) {
            Set<Entity> impacted = new LinkedHashSet<>();
            Queue<Entity> queue = new ArrayDeque<>();
            queue.add(node);
            impacted.add(node);

            while (!queue.isEmpty()) {
                Entity current = queue.poll();
                for (RelationshipEdge edge : graph.incomingEdgesOf(current)) {
                    Entity source = graph.getEdgeSource(edge);
                    if (impacted.add(source)) {
                        queue.add(source);
                    }
                }
            }
            result.put(node, impacted);
        }
        return result;
    }

    private Set<Entity> findDeadCode(Graph<Entity, RelationshipEdge> graph) {
        Set<Entity> entryPoints = new LinkedHashSet<>();

        // 1. Search for main methods first
        for (Entity entity : graph.vertexSet()) {
            if (entity.type() == EntityType.METHOD && "main".equals(entity.name())) {
                entryPoints.add(entity);
                // Also treat any containing class/interface as an entry point
                for (RelationshipEdge edge : graph.incomingEdgesOf(entity)) {
                    Entity source = graph.getEdgeSource(edge);
                    if (source.type() == EntityType.CLASS || source.type() == EntityType.INTERFACE) {
                        entryPoints.add(source);
                    }
                }
            }
        }

        // 2. If no main methods found, scan for root classes/interfaces (in-degree from other classes = 0)
        if (entryPoints.isEmpty()) {
            for (Entity entity : graph.vertexSet()) {
                if (entity.type() == EntityType.CLASS || entity.type() == EntityType.INTERFACE) {
                    boolean hasExternalIncoming = false;
                    for (RelationshipEdge edge : graph.incomingEdgesOf(entity)) {
                        Entity source = graph.getEdgeSource(edge);
                        if (!source.equals(entity) && source.type() != EntityType.METHOD) {
                            hasExternalIncoming = true;
                            break;
                        }
                    }
                    if (!hasExternalIncoming) {
                        entryPoints.add(entity);
                    }
                }
            }
        }

        // 3. Fallback: use all classes/interfaces as entry points
        if (entryPoints.isEmpty()) {
            for (Entity entity : graph.vertexSet()) {
                if (entity.type() == EntityType.CLASS || entity.type() == EntityType.INTERFACE) {
                    entryPoints.add(entity);
                }
            }
        }

        // If graph is empty or has no structural roots, return empty dead code
        if (entryPoints.isEmpty()) {
            return Collections.emptySet();
        }

        // BFS traversal forward from entry points
        Set<Entity> reachable = new LinkedHashSet<>(entryPoints);
        Queue<Entity> queue = new ArrayDeque<>(entryPoints);

        while (!queue.isEmpty()) {
            Entity current = queue.poll();
            for (RelationshipEdge edge : graph.outgoingEdgesOf(current)) {
                Entity target = graph.getEdgeTarget(edge);
                if (reachable.add(target)) {
                    queue.add(target);
                }
            }
        }

        Set<Entity> deadCode = new LinkedHashSet<>(graph.vertexSet());
        deadCode.removeAll(reachable);
        return deadCode;
    }

    private List<Set<Entity>> findCycles(Graph<Entity, RelationshipEdge> graph) {
        KosarajuStrongConnectivityInspector<Entity, RelationshipEdge> inspector =
                new KosarajuStrongConnectivityInspector<>(graph);

        List<Set<Entity>> cycles = new ArrayList<>();
        for (Set<Entity> scc : inspector.stronglyConnectedSets()) {
            if (scc.size() > 1) {
                cycles.add(scc);
            } else if (scc.size() == 1) {
                // Check if there is a self-loop
                Entity node = scc.iterator().next();
                if (graph.containsEdge(node, node)) {
                    cycles.add(scc);
                }
            }
        }
        return cycles;
    }
}
