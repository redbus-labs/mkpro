package com.mkpro.scripting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mkpro.CentralMemory;

import java.time.Instant;
import java.util.*;

/**
 * Repository for user-defined Groovy scripts, stored in CentralMemory.
 * 
 * Scripts are stored with key prefix "script:" in the shared memories map.
 * This makes them available across sessions and (via SyncEngine) across instances.
 *
 * Storage format per script:
 * key:   "script:<name>"
 * value: JSON string {"code": "...", "description": "...", "created": "...", "lastUsed": "...", "usageCount": N}
 */
public class ScriptRepository {

    private static final String KEY_PREFIX = "script:";
    private static final ObjectMapper mapper = new ObjectMapper();

    private final CentralMemory memory;

    public ScriptRepository(CentralMemory memory) {
        this.memory = memory;
    }

    /**
     * Save a script to the repository. Validates before saving.
     *
     * @param name Unique script name (alphanumeric + underscores)
     * @param code Groovy source code
     * @param description Human-readable description of what the script does
     * @return null on success, error message on failure
     */
    public String save(String name, String code, String description) {
        // Validate name
        if (name == null || !name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            return "Invalid script name. Use alphanumeric + underscores, start with letter.";
        }

        // Validate code safety
        String validationError = ScriptEngine.validate(code);
        if (validationError != null) {
            return validationError;
        }

        // Build metadata as JSON
        try {
            ObjectNode meta = mapper.createObjectNode();
            meta.put("code", code);
            meta.put("description", description != null ? description : "");
            meta.put("created", Instant.now().toString());
            meta.put("lastUsed", "");
            meta.put("usageCount", 0);

            memory.saveMemory(KEY_PREFIX + name, meta.toString());
            return null; // Success
        } catch (Exception e) {
            return "Failed to save script: " + e.getMessage();
        }
    }

    /**
     * Load a script by name. Returns null if not found.
     */
    public ScriptEntry load(String name) {
        String json = memory.getMemory(KEY_PREFIX + name);
        if (json == null || json.isEmpty()) return null;

        try {
            ObjectNode node = (ObjectNode) mapper.readTree(json);
            return new ScriptEntry(
                name,
                node.get("code").asText(),
                node.has("description") ? node.get("description").asText() : "",
                node.has("created") ? node.get("created").asText() : "",
                node.has("lastUsed") ? node.get("lastUsed").asText() : "",
                node.has("usageCount") ? node.get("usageCount").asInt() : 0
            );
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Record a usage of the script (updates lastUsed and usageCount).
     */
    public void recordUsage(String name) {
        ScriptEntry entry = load(name);
        if (entry == null) return;

        try {
            ObjectNode meta = mapper.createObjectNode();
            meta.put("code", entry.code);
            meta.put("description", entry.description);
            meta.put("created", entry.created);
            meta.put("lastUsed", Instant.now().toString());
            meta.put("usageCount", entry.usageCount + 1);

            memory.saveMemory(KEY_PREFIX + name, meta.toString());
        } catch (Exception e) {
            // Silent — usage tracking is best-effort
        }
    }

    /**
     * List all scripts in the repository.
     */
    public List<ScriptEntry> listAll() {
        List<ScriptEntry> scripts = new ArrayList<>();
        Map<String, String> allMemories = memory.getAllMemories();
        
        for (Map.Entry<String, String> entry : allMemories.entrySet()) {
            if (entry.getKey().startsWith(KEY_PREFIX)) {
                String name = entry.getKey().substring(KEY_PREFIX.length());
                try {
                    ObjectNode node = (ObjectNode) mapper.readTree(entry.getValue());
                    scripts.add(new ScriptEntry(
                        name,
                        node.get("code").asText(),
                        node.has("description") ? node.get("description").asText() : "",
                        node.has("created") ? node.get("created").asText() : "",
                        node.has("lastUsed") ? node.get("lastUsed").asText() : "",
                        node.has("usageCount") ? node.get("usageCount").asInt() : 0
                    ));
                } catch (Exception e) {
                    // Skip malformed entries
                }
            }
        }
        scripts.sort(Comparator.comparing(s -> s.name));
        return scripts;
    }

    /**
     * Delete a script by name. Returns true if deleted, false if not found.
     */
    public boolean delete(String name) {
        String existing = memory.getMemory(KEY_PREFIX + name);
        if (existing == null || existing.isEmpty()) return false;
        // Save empty to "delete" (CentralMemory doesn't have delete, but empty = removed)
        memory.saveMemory(KEY_PREFIX + name, "");
        return true;
    }

    /**
     * A stored script entry with metadata.
     */
    public static class ScriptEntry {
        public final String name;
        public final String code;
        public final String description;
        public final String created;
        public final String lastUsed;
        public final int usageCount;

        public ScriptEntry(String name, String code, String description, String created, String lastUsed, int usageCount) {
            this.name = name;
            this.code = code;
            this.description = description;
            this.created = created;
            this.lastUsed = lastUsed;
            this.usageCount = usageCount;
        }
    }
}
