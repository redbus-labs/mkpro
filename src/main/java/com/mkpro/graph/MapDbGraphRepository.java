package com.mkpro.graph;

import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Disk-backed transactional implementation of GraphRepository utilizing MapDB HTreeMaps.
 * Safely falls back to an in-memory store if the disk cache is locked or inaccessible.
 */
public class MapDbGraphRepository implements GraphRepository {
    private final DB db;
    private final HTreeMap<String, ExtractionResult> store;
    private final List<GraphListener> listeners = new ArrayList<>();

    public interface GraphListener {
        void onGraphUpdated(String key, ExtractionResult result);
    }

    public void addListener(GraphListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Resolves a centralized cache path for the given project workspace inside the user's home directory.
     * Format: ~/.graphify/<project-name>_<path-hash>/graphify.db
     */
    public static String resolveDatabasePath(Path projectDir) {
        String userHome = System.getProperty("user.home");
        File centralDir = new File(userHome, ".graphify");
        
        Path normalizedPath = projectDir.toAbsolutePath().normalize();
        String projectName = normalizedPath.getFileName() != null ? normalizedPath.getFileName().toString() : "default";
        
        String pathStr = normalizedPath.toString();
        String hashSuffix = getDeterministicHash(pathStr);
        
        File projectCacheDir = new File(centralDir, projectName + "_" + hashSuffix);
        return new File(projectCacheDir, "graphify.db").getAbsolutePath();
    }

    private static String getDeterministicHash(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.substring(0, 8); // Keep it short (8 characters)
        } catch (Exception e) {
            // Fallback to simple hashCode hex if SHA-256 fails
            return Integer.toHexString(input.hashCode());
        }
    }

    public MapDbGraphRepository(String dbPath) {
        DB tempDb = null;
        HTreeMap<String, ExtractionResult> tempStore = null;

        try {
            // Ensure the directory for the database file exists
            File dbFile = new File(dbPath);
            File parentDir = dbFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            tempDb = DBMaker.fileDB(dbPath)
                    .transactionEnable()
                    .closeOnJvmShutdown()
                    .make();

            tempStore = tempDb.hashMap("extractions")
                    .keySerializer(Serializer.STRING)
                    .valueSerializer(new ExtractionResultSerializer())
                    .createOrOpen();
        } catch (Exception e) {
            System.err.println("  [Warning] Cache database is locked or inaccessible: " + e.getMessage());
            System.err.println("  [Warning] Falling back to temporary in-memory cache. Results will not be saved on disk.");
            
            try {
                tempDb = DBMaker.memoryDB()
                        .transactionEnable()
                        .closeOnJvmShutdown()
                        .make();

                tempStore = tempDb.hashMap("extractions")
                        .keySerializer(Serializer.STRING)
                        .valueSerializer(new ExtractionResultSerializer())
                        .createOrOpen();
            } catch (Exception innerEx) {
                System.err.println("  [Error] Failed to initialize in-memory fallback database: " + innerEx.getMessage());
                throw innerEx;
            }
        }

        this.db = tempDb;
        this.store = tempStore;
    }

    @Override
    public void saveExtraction(String commitHash, ExtractionResult extraction) {
        if (commitHash == null || extraction == null) {
            return;
        }
        store.put(commitHash, extraction);
        db.commit(); // Transactional commit to disk

        // Notify listeners after commit
        for (GraphListener listener : listeners) {
            listener.onGraphUpdated(commitHash, extraction);
        }
    }

    public void mergeExtraction(String key, ExtractionResult remoteResult) {
        if (key == null || remoteResult == null) {
            return;
        }

        ExtractionResult localResult = store.get(key);
        if (localResult == null) {
            store.put(key, remoteResult);
        } else {
            List<Entity> mergedEntities = new ArrayList<>(localResult.entities());
            Set<String> existingEntityIds = mergedEntities.stream()
                .map(Entity::id)
                .collect(Collectors.toSet());

            for (Entity entity : remoteResult.entities()) {
                if (!existingEntityIds.contains(entity.id())) {
                    mergedEntities.add(entity);
                }
            }

            List<Relationship> mergedRelationships = new ArrayList<>(localResult.relationships());
            Set<String> existingRelIds = mergedRelationships.stream()
                .map(Relationship::id)
                .collect(Collectors.toSet());

            for (Relationship rel : remoteResult.relationships()) {
                if (!existingRelIds.contains(rel.id())) {
                    mergedRelationships.add(rel);
                }
            }

            store.put(key, new ExtractionResult(mergedEntities, mergedRelationships));
        }
        db.commit();
        // Do NOT notify listeners to prevent network loops
    }

    @Override
    public Optional<ExtractionResult> loadExtraction(String commitHash) {
        if (commitHash == null) {
            return Optional.empty();
        }
        ExtractionResult result = store.get(commitHash);
        return Optional.ofNullable(result);
    }

    @Override
    public boolean hasExtraction(String commitHash) {
        if (commitHash == null) {
            return false;
        }
        return store.containsKey(commitHash);
    }

    @Override
    public void deleteExtraction(String commitHash) {
        if (commitHash == null) {
            return;
        }
        store.remove(commitHash);
        db.commit();
    }

    @Override
    public void close() {
        if (!db.isClosed()) {
            db.close();
        }
    }
}
