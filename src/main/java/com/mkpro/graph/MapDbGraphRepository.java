package com.mkpro.graph;

import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Disk-backed transactional implementation of GraphRepository utilizing MapDB HTreeMaps.
 * Safely falls back to an in-memory store if the disk cache is locked or inaccessible.
 */
public class MapDbGraphRepository implements GraphRepository {
    private final DB db;
    private final HTreeMap<String, ExtractionResult> store;

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
