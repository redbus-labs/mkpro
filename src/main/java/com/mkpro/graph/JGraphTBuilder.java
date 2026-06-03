package com.mkpro.graph;

import org.jgrapht.Graph;
import org.jgrapht.graph.DirectedWeightedMultigraph;

import java.util.HashMap;
import java.util.Map;

public class JGraphTBuilder implements GraphBuilder {
    @Override
    public Graph<Entity, RelationshipEdge> buildGraph(ExtractionResult result) {
        Graph<Entity, RelationshipEdge> graph = new DirectedWeightedMultigraph<>(RelationshipEdge.class);
        Map<String, Entity> entityMap = new HashMap<>();

        for (Entity entity : result.entities()) {
            graph.addVertex(entity);
            entityMap.put(entity.id(), entity);
        }

        for (Relationship rel : result.relationships()) {
            Entity source = entityMap.get(rel.sourceId());
            Entity target = entityMap.get(rel.targetId());

            if (source != null && target != null) {
                RelationshipEdge edge = new RelationshipEdge(rel.type().name());
                graph.addEdge(source, target, edge);
                graph.setEdgeWeight(edge, 1.0);
            }
        }

        return graph;
    }
}
