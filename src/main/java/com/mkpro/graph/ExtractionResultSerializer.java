package com.mkpro.graph;

import org.mapdb.DataInput2;
import org.mapdb.DataOutput2;
import org.mapdb.Serializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Root MapDB serializer for the multi-collection ExtractionResult record.
 * Composes EntitySerializer and RelationshipSerializer.
 */
public class ExtractionResultSerializer implements Serializer<ExtractionResult> {
    private static final EntitySerializer ENTITY_SERIALIZER = new EntitySerializer();
    private static final RelationshipSerializer RELATIONSHIP_SERIALIZER = new RelationshipSerializer();

    @Override
    public void serialize(DataOutput2 out, ExtractionResult value) throws IOException {
        if (value == null) {
            out.packInt(0);
            out.packInt(0);
            return;
        }

        // 1. Serialize Entities List
        List<Entity> entities = value.entities();
        if (entities == null) {
            out.packInt(0);
        } else {
            out.packInt(entities.size());
            for (Entity entity : entities) {
                ENTITY_SERIALIZER.serialize(out, entity);
            }
        }

        // 2. Serialize Relationships List
        List<Relationship> relationships = value.relationships();
        if (relationships == null) {
            out.packInt(0);
        } else {
            out.packInt(relationships.size());
            for (Relationship relationship : relationships) {
                RELATIONSHIP_SERIALIZER.serialize(out, relationship);
            }
        }
    }

    @Override
    public ExtractionResult deserialize(DataInput2 in, int available) throws IOException {
        // 1. Deserialize Entities
        int entityCount = in.unpackInt();
        List<Entity> entities = new ArrayList<>(entityCount);
        for (int i = 0; i < entityCount; i++) {
            entities.add(ENTITY_SERIALIZER.deserialize(in, -1));
        }

        // 2. Deserialize Relationships
        int relationshipCount = in.unpackInt();
        List<Relationship> relationships = new ArrayList<>(relationshipCount);
        for (int i = 0; i < relationshipCount; i++) {
            relationships.add(RELATIONSHIP_SERIALIZER.deserialize(in, -1));
        }

        return new ExtractionResult(entities, relationships);
    }
}
