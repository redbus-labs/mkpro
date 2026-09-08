package com.mkpro.graph;

import org.jgrapht.graph.DefaultWeightedEdge;

public class RelationshipEdge extends DefaultWeightedEdge {
    private final String type;

    public RelationshipEdge(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }
}
