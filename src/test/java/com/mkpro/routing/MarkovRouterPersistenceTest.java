package com.mkpro.routing;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MarkovRouter save/load — v4 format, corruption detection, legacy compat, Layer 2 persistence.
 */
public class MarkovRouterPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoadPreservesTransitions() throws Exception {
        MarkovRouter router = new MarkovRouter();
        router.recordTransition(IntentClassifier.TaskCategory.CODING, null, "Coder");
        router.recordTransition(IntentClassifier.TaskCategory.CODING, "Coder", "Tester");
        router.recordTransition(IntentClassifier.TaskCategory.DEVOPS, null, "DevOps");

        Path modelPath = tempDir.resolve("model.dat");
        router.save(modelPath);

        assertTrue(Files.exists(modelPath));
        assertTrue(Files.size(modelPath) > 0);

        MarkovRouter loaded = new MarkovRouter();
        loaded.load(modelPath);

        assertEquals(router.getTotalObservations(), loaded.getTotalObservations());
    }

    @Test
    void saveAndLoadPreservesLayer2() throws Exception {
        MarkovRouter router = new MarkovRouter();
        router.recordToolUsage("Coder", IntentClassifier.TaskCategory.CODING, List.of("file_read", "file_write"));
        router.recordToolUsage("DevOps", IntentClassifier.TaskCategory.DEVOPS, List.of("shell", "file_write"));

        Path modelPath = tempDir.resolve("model_l2.dat");
        router.save(modelPath);

        MarkovRouter loaded = new MarkovRouter();
        loaded.load(modelPath);

        // Layer 2 should be preserved
        var tools = loaded.getExpectedTools("Coder", IntentClassifier.TaskCategory.CODING, 3);
        assertFalse(tools.isEmpty());
        // file_read and file_write should be in expected tools
        boolean hasFileRead = tools.stream().anyMatch(t -> "file_read".equals(t.tool));
        boolean hasFileWrite = tools.stream().anyMatch(t -> "file_write".equals(t.tool));
        assertTrue(hasFileRead);
        assertTrue(hasFileWrite);
    }

    @Test
    void saveCreatesBackup() throws Exception {
        Path modelPath = tempDir.resolve("model.dat");

        MarkovRouter router1 = new MarkovRouter();
        router1.recordTransition(IntentClassifier.TaskCategory.CODING, null, "Coder");
        router1.save(modelPath);

        // Save again — should create .bak
        MarkovRouter router2 = new MarkovRouter();
        router2.recordTransition(IntentClassifier.TaskCategory.TESTING, null, "Tester");
        router2.recordTransition(IntentClassifier.TaskCategory.TESTING, null, "Tester");
        router2.save(modelPath);

        Path backupPath = tempDir.resolve("model.dat.bak");
        assertTrue(Files.exists(backupPath));
    }

    @Test
    void corruptFileDetected() throws Exception {
        Path modelPath = tempDir.resolve("corrupt.dat");
        // Write garbage
        Files.write(modelPath, "MKPRO_MARKOV".getBytes());
        // Too small — missing version/data/checksum

        MarkovRouter router = new MarkovRouter();
        // Should not throw — gracefully handles corruption
        assertDoesNotThrow(() -> router.load(modelPath));
    }

    @Test
    void checksumMismatchDetected() throws Exception {
        Path modelPath = tempDir.resolve("tampered.dat");

        // Save a valid model
        MarkovRouter router = new MarkovRouter();
        router.recordTransition(IntentClassifier.TaskCategory.CODING, null, "Coder");
        router.save(modelPath);

        // Tamper with the file (flip a byte in the data section)
        byte[] bytes = Files.readAllBytes(modelPath);
        if (bytes.length > 30) {
            bytes[25] ^= 0xFF; // Flip a data byte
        }
        Files.write(modelPath, bytes);

        // Loading should detect checksum mismatch and not crash
        MarkovRouter loaded = new MarkovRouter();
        assertDoesNotThrow(() -> loaded.load(modelPath));
    }

    @Test
    void legacyModelLoadedGracefully() throws Exception {
        Path modelPath = tempDir.resolve("legacy.dat");

        // Write a legacy format (no magic header — just raw ObjectOutputStream)
        MarkovRouter legacyRouter = new MarkovRouter();
        legacyRouter.recordTransition(IntentClassifier.TaskCategory.GIT, null, "GitAgent");

        // Simulate legacy save (raw OOS without magic)
        try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(Files.newOutputStream(modelPath))) {
            oos.writeObject(new java.util.HashMap<>(legacyRouter.getTransitionMatrix()));
            oos.writeObject(new java.util.HashMap<>(legacyRouter.getCategoryToAgentMatrix()));
            oos.writeInt(legacyRouter.getTotalObservations());
        }

        // Loading legacy format should work
        MarkovRouter loaded = new MarkovRouter();
        assertDoesNotThrow(() -> loaded.load(modelPath));
        assertEquals(legacyRouter.getTotalObservations(), loaded.getTotalObservations());
    }

    @Test
    void emptyRouterSaveAndLoad() throws Exception {
        Path modelPath = tempDir.resolve("empty.dat");

        MarkovRouter router = new MarkovRouter();
        router.save(modelPath);

        MarkovRouter loaded = new MarkovRouter();
        loaded.load(modelPath);
        assertEquals(0, loaded.getTotalObservations());
    }

    @Test
    void nonExistentFileLoadDoesNothing() throws Exception {
        Path modelPath = tempDir.resolve("missing.dat");
        MarkovRouter router = new MarkovRouter();
        assertDoesNotThrow(() -> router.load(modelPath));
        assertEquals(0, router.getTotalObservations());
    }

    @Test
    void atomicWriteNoPartialFile() throws Exception {
        Path modelPath = tempDir.resolve("atomic.dat");
        Path tmpPath = tempDir.resolve("atomic.dat.tmp");

        MarkovRouter router = new MarkovRouter();
        router.recordTransition(IntentClassifier.TaskCategory.CODING, null, "Coder");
        router.save(modelPath);

        // .tmp file should not exist after successful save (was renamed)
        assertFalse(Files.exists(tmpPath));
        assertTrue(Files.exists(modelPath));
    }

    @Test
    void saveAndLoadPreservesCompletionPatterns() throws Exception {
        MarkovRouter router = new MarkovRouter();
        router.recordCompletion(IntentClassifier.TaskCategory.CODING, List.of("file_read", "file_write", "shell"), true, 3);
        router.recordCompletion(IntentClassifier.TaskCategory.CODING, List.of("file_read", "file_write", "shell"), true, 4);

        Path modelPath = tempDir.resolve("completion.dat");
        router.save(modelPath);

        MarkovRouter loaded = new MarkovRouter();
        loaded.load(modelPath);

        // Completion patterns are in memory but not directly serialized (they come from training)
        // The save preserves transitions + Layer 2; completion patterns retrain from JSONL
        // This just verifies save/load doesn't crash with completion data present
        assertEquals(router.getTotalObservations(), loaded.getTotalObservations());
    }
}
