package com.mkpro.graph;

import java.util.List;

public record ExtractionResult(
    List<Entity> entities,
    List<Relationship> relationships
) {}
