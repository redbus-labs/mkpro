package com.mkpro.graph;

import org.jgrapht.Graph;

public interface GraphAnalyzer {
    AnalysisResult analyze(Graph<Entity, RelationshipEdge> graph);
}
