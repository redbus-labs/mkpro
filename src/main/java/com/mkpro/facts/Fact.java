package com.mkpro.facts;

import java.io.Serializable;
import java.util.UUID;

public record Fact(
    String id,
    String category,
    String topic,
    String fact,
    double confidence,
    long timestamp
) implements Serializable {

    public Fact(String category, String topic, String fact, double confidence) {
        this(UUID.randomUUID().toString(), category, topic, fact, confidence, System.currentTimeMillis());
    }

    public Fact(String category, String topic, String fact) {
        this(category, topic, fact, 1.0);
    }

    public Fact(String id, String category, String topic, String fact, double confidence) {
        this(id, category, topic, fact, confidence, System.currentTimeMillis());
    }
}
