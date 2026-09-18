package com.mkpro.graph;

import java.util.Map;

/** Represents a single entity in the code graph. Added by CodeEditor. */
public record Entity(
    String id,
    String name,
    EntityType type,
    Map<String, Object> metadata
) {
    public static final String VERSION = "1.0.0";
}
