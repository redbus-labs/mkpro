package com.mkpro.events;

import com.mkpro.core.MkProContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Turn-Level Change Summary & Rollback (EditApprovalService)
 * and Self-Healing Auto-Verify Gate (AutoVerifyGate & MkProContext).
 */
public class EditTurnRollbackAndVerifyTest {

    private EditApprovalService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new EditApprovalService();
    }

    @Test
    void recordMultipleEditsPreservesPreTurnSnapshotAndComputesCumulativeDiff() throws Exception {
        Path existingFile = tempDir.resolve("Existing.java");
        String original = "line1\nline2\n";
        Files.writeString(existingFile, original);

        // First edit to existing file
        String edit1 = "line1\nline2_mod1\n";
        Files.writeString(existingFile, edit1);
        EditProposal prop1 = new EditProposal("p1", "src/Existing.java", original, edit1, false);
        service.recordAppliedEdit(prop1, existingFile, true);

        // Second edit to the same file in the same turn
        String edit2 = "line1\nline2_mod2\nline3\n";
        Files.writeString(existingFile, edit2);
        EditProposal prop2 = new EditProposal("p2", "src/Existing.java", edit1, edit2, false);
        service.recordAppliedEdit(prop2, existingFile, true);

        // New file created in the same turn
        Path newFile = tempDir.resolve("NewCreated.java");
        String newContent = "public class NewCreated {}\n";
        Files.writeString(newFile, newContent);
        EditProposal prop3 = new EditProposal("p3", "src/NewCreated.java", "", newContent, true);
        service.recordAppliedEdit(prop3, newFile, false);

        Map<String, EditApprovalService.TurnEditRecord> snapshots = service.getTurnSnapshots();
        assertEquals(2, snapshots.size());
        assertTrue(service.getCurrentTurnModifiedFiles().contains("src/Existing.java"));
        assertTrue(service.getCurrentTurnModifiedFiles().contains("src/NewCreated.java"));

        EditApprovalService.TurnEditRecord existingRec = snapshots.get("src/Existing.java");
        assertNotNull(existingRec);
        assertTrue(existingRec.isExistedBeforeFirstEdit());
        assertEquals(original, existingRec.getOriginalContent());
        assertEquals(edit2, existingRec.getCurrentContent());
        assertTrue(existingRec.getAddedCount() >= 2);
        assertTrue(existingRec.getRemovedCount() >= 1);

        EditApprovalService.TurnEditRecord newRec = snapshots.get("src/NewCreated.java");
        assertNotNull(newRec);
        assertFalse(newRec.isExistedBeforeFirstEdit());
        assertEquals("", newRec.getOriginalContent());
        assertEquals(newContent, newRec.getCurrentContent());
    }

    @Test
    void revertSingleFileRestoresExistingAndDeletesNewlyCreated() throws Exception {
        Path existingFile = tempDir.resolve("Config.yaml");
        String initialContent = "port: 8080\n";
        Files.writeString(existingFile, initialContent);

        String updatedContent = "port: 9090\n";
        Files.writeString(existingFile, updatedContent);
        service.recordAppliedEdit(
            new EditProposal("e1", "Config.yaml", initialContent, updatedContent, false),
            existingFile,
            true
        );

        Path createdFile = tempDir.resolve("TempHelper.java");
        Files.writeString(createdFile, "class TempHelper {}");
        service.recordAppliedEdit(
            new EditProposal("e2", "TempHelper.java", "", "class TempHelper {}", true),
            createdFile,
            false
        );

        // Revert single existing file
        assertTrue(service.revertFile("Config.yaml"));
        assertEquals(initialContent, Files.readString(existingFile));
        assertFalse(service.getTurnSnapshots().containsKey("Config.yaml"));
        assertTrue(service.getTurnSnapshots().containsKey("TempHelper.java"));

        // Revert newly created file -> should delete it from disk
        assertTrue(service.revertFile("TempHelper.java"));
        assertFalse(Files.exists(createdFile));
        assertTrue(service.getTurnSnapshots().isEmpty());
    }

    @Test
    void revertTurnRestoresAllModifiedFilesAndDeletesCreatedFiles() throws Exception {
        Path fileA = tempDir.resolve("ServiceA.java");
        Path fileB = tempDir.resolve("ServiceB.java");
        Path fileNew = tempDir.resolve("BrandNew.java");

        Files.writeString(fileA, "class ServiceA { int v = 1; }");
        Files.writeString(fileB, "class ServiceB { int v = 1; }");

        // Apply turn edits
        Files.writeString(fileA, "class ServiceA { int v = 2; }");
        service.recordAppliedEdit(
            new EditProposal("1", "ServiceA.java", "class ServiceA { int v = 1; }", "class ServiceA { int v = 2; }", false),
            fileA,
            true
        );

        Files.writeString(fileB, "class ServiceB { int v = 2; }");
        service.recordAppliedEdit(
            new EditProposal("2", "ServiceB.java", "class ServiceB { int v = 1; }", "class ServiceB { int v = 2; }", false),
            fileB,
            true
        );

        Files.writeString(fileNew, "class BrandNew {}");
        service.recordAppliedEdit(
            new EditProposal("3", "BrandNew.java", "", "class BrandNew {}", true),
            fileNew,
            false
        );

        assertEquals(3, service.getTurnSnapshots().size());

        List<String> reverted = service.revertTurn();
        assertEquals(3, reverted.size());
        assertEquals("class ServiceA { int v = 1; }", Files.readString(fileA));
        assertEquals("class ServiceB { int v = 1; }", Files.readString(fileB));
        assertFalse(Files.exists(fileNew));
        assertTrue(service.getTurnSnapshots().isEmpty());
        assertTrue(service.getCurrentTurnModifiedFiles().isEmpty());
    }

    @Test
    void startNewTurnPreservesSnapshotsUntilNextEditOccurs() throws Exception {
        Path fileA = tempDir.resolve("Alpha.txt");
        Files.writeString(fileA, "v2");
        service.recordAppliedEdit(new EditProposal("1", "Alpha.txt", "v1", "v2", false), fileA, true);

        service.startNewTurn();
        // Modified files for current turn cleared, but snapshot remains available for rollback before next edit
        assertTrue(service.getCurrentTurnModifiedFiles().isEmpty());
        assertEquals(1, service.getTurnSnapshots().size());

        // First edit of new turn clears old turn's snapshots
        Path fileB = tempDir.resolve("Beta.txt");
        Files.writeString(fileB, "newB");
        service.recordAppliedEdit(new EditProposal("2", "Beta.txt", "", "newB", true), fileB, false);

        assertEquals(1, service.getTurnSnapshots().size());
        assertFalse(service.getTurnSnapshots().containsKey("Alpha.txt"));
        assertTrue(service.getTurnSnapshots().containsKey("Beta.txt"));
    }

    @Test
    void mkProContextAutoVerifyStateAndAutoVerifyGateBehavior() {
        MkProContext context = new MkProContext();
        assertFalse(context.isAutoVerify());
        assertTrue(context.isLastVerifyPassed());
        assertEquals("Not run yet", context.getLastVerifySummary());

        // When auto-verify is disabled and forceRun is false, verifyFiles skips execution
        AutoVerifyGate.VerifyResult skipped = AutoVerifyGate.verifyFiles(
            context, List.of("src/Main.java"), false
        );
        assertFalse(skipped.ran());
        assertTrue(skipped.passed());
        assertTrue(skipped.summary().contains("Skipped"));

        // Enable auto-verify
        context.setAutoVerify(true);
        assertTrue(context.isAutoVerify());

        // When only non-code files are modified, verifyFiles skips cleanly
        AutoVerifyGate.VerifyResult nonCode = AutoVerifyGate.verifyFiles(
            context, List.of("README.md", "docs/notes.txt"), false
        );
        assertFalse(nonCode.ran());
        assertTrue(nonCode.passed());
        assertTrue(nonCode.summary().contains("No verifiable source files"));

        // Test buildSelfHealPrompt formatting
        AutoVerifyGate.VerifyResult failedResult = new AutoVerifyGate.VerifyResult(
            true,
            false,
            "mvn test-compile -q",
            "FAILED (exit 1: mvn test-compile -q)",
            "[ERROR] Foo.java:[12,5] ';' expected",
            List.of("src/Foo.java")
        );
        String prompt = AutoVerifyGate.buildSelfHealPrompt(failedResult);
        assertTrue(prompt.contains("[Auto-Verify Self-Heal]"));
        assertTrue(prompt.contains("mvn test-compile -q"));
        assertTrue(prompt.contains("src/Foo.java"));
        assertTrue(prompt.contains("[ERROR] Foo.java:[12,5] ';' expected"));

        context.setLastVerifyResult(false, failedResult.summary());
        assertFalse(context.isLastVerifyPassed());
        assertEquals("FAILED (exit 1: mvn test-compile -q)", context.getLastVerifySummary());
    }
}
