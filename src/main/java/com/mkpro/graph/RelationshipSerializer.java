package com.mkpro.graph;

import org.mapdb.DataInput2;
import org.mapdb.DataOutput2;
import org.mapdb.Serializer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Highly optimized, compact MapDB serializer for the Relationship record.
 */
public class RelationshipSerializer implements Serializer<Relationship> {

    @Override
    public void serialize(DataOutput2 out, Relationship value) throws IOException {
        out.writeUTF(value.id());
        out.writeUTF(value.sourceId());
        out.writeUTF(value.targetId());
        out.writeByte(value.type().ordinal());
        
        // Serialize Metadata Map
        Map<String, Object> metadata = value.metadata();
        if (metadata == null) {
            out.packInt(0);
        } else {
            out.packInt(metadata.size());
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                out.writeUTF(entry.getKey());
                Object val = entry.getValue();
                if (val == null) {
                    out.writeByte(0); // Null type
                } else if (val instanceof String) {
                    out.writeByte(1);
                    out.writeUTF((String) val);
                } else if (val instanceof Integer) {
                    out.writeByte(2);
                    out.writeInt((Integer) val);
                } else if (val instanceof Long) {
                    out.writeByte(3);
                    out.writeLong((Long) val);
                } else if (val instanceof Double) {
                    out.writeByte(4);
                    out.writeDouble((Double) val);
                } else if (val instanceof Boolean) {
                    out.writeByte(5);
                    out.writeBoolean((Boolean) val);
                } else {
                    out.writeByte(1); // Fallback to string serialization
                    out.writeUTF(val.toString());
                }
            }
        }
    }

    @Override
    public Relationship deserialize(DataInput2 in, int available) throws IOException {
        String id = in.readUTF();
        String sourceId = in.readUTF();
        String targetId = in.readUTF();
        RelType type = RelType.values()[in.readByte()];
        
        int size = in.unpackInt();
        Map<String, Object> metadata = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            String key = in.readUTF();
            byte valType = in.readByte();
            Object value = null;
            switch (valType) {
                case 0 -> value = null;
                case 1 -> value = in.readUTF();
                case 2 -> value = in.readInt();
                case 3 -> value = in.readLong();
                case 4 -> value = in.readDouble();
                case 5 -> value = in.readBoolean();
            }
            metadata.put(key, value);
        }
        return new Relationship(id, sourceId, targetId, type, metadata);
    }
}
