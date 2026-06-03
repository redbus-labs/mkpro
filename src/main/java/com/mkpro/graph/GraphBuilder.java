package com.mkpro.graph;

import org.jgrapht.Graph;

public interface GraphBuilder {
    Graph<Entity, RelationshipEdge> buildGraph(ExtractionResult result);
}
