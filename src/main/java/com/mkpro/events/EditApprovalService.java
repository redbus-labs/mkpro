package com.mkpro.events;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util. LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages pending edit proposals, their approval/rejection, and turn-level edit snapshots
 * for batch diff review and one-click checkpoint rollback.
 * 
 * Flow:
 * 1. safe_write_file creates proposal → submitProposal() returns a Future
 * 2. Sinks display the proposal (terminal: auto-approve timer, web: diff viewer)
 * 3. Any channel calls approve(id) or reject(id) → completes the Future
 * 4. safe_write_file unblocks and acts accordingly
 * 5. Applied edits are recorded in activeTurnSnapshots for turn summary and rollback
 */
public class EditApprovalService {

    /** Singleton instance — set during bootstrap, accessed by file edit tools */
    public static volatile EditApprovalService INSTANCE;

    private final Map<String, PendingEdit> pendingEdits = new ConcurrentHashMap<>();
    private final Map<String, TurnEditRecord> activeTurnSnapshots = new LinkedHashMap<>();
    private final Set<String> currentTurnModifiedFiles = ConcurrentHashMap.newKeySet();
    private boolean pendingNewTurnClear = false;

    public static class TurnEditRecord {
        public final String filePath;
        public final String resolvedPath;
        public final String originalContent;
        public String currentContent;
        public final boolean existedBeforeFirstEdit;
        public long timestamp;
        public List<EditProposal.DiffLine> cumulativeDiff;

        public TurnEditRecord(String filePath, String resolvedPath, String originalContent,
                              String currentContent, boolean existedBeforeFirstEdit,
                              long timestamp, List<EditProposal.DiffLine> cumulativeDiff) {
            this.filePath = filePath;
            this.resolvedPath = resolvedPath;
            this.originalContent = originalContent != null ? originalContent : "";
            this.currentContent = currentContent != null ? currentContent : "";
            this.existedBeforeFirstEdit = existedBeforeFirstEdit;
            this.timestamp = timestamp;
            this.cumulativeDiff = cumulativeDiff != null ? cumulativeDiff : new ArrayList<>();
        }

        public String getFilePath() { return filePath; }
        public String getResolvedPath() { return resolvedPath; }
        public String getOriginalContent() { return originalContent; }
        public String getCurrentContent() { return currentContent; }
        public boolean isExistedBeforeFirstEdit() { return existedBeforeFirstEdit; }
        public long getTimestamp() { return timestamp; }
        public List<EditProposal.DiffLine> getCumulativeDiff() { return cumulativeDiff; }

        public int getAddedCount() {
            if (cumulativeDiff == null) return 0;
            int count = 0;
            for (EditProposal.DiffLine d : cumulativeDiff) {
                if (d.getType() == EditProposal.DiffLine.Type.ADDED) count++;
            }
            return count;
        }

        public int getRemovedCount() {
            if (cumulativeDiff == null) return 0;
            int count = 0;
            for (EditProposal.DiffLine d : cumulativeDiff) {
                if (d.getType() == EditProposal.DiffLine.Type.REMOVED) count++;
            }
            return count;
        }
    }

    /**
     * Marks the start of a new user-initiated turn.
     * Clears currentTurnModifiedFiles immediately, and marks activeTurnSnapshots to be cleared
     * when the first new edit of the subsequent turn occurs (preserving revert capability until then).
     */
    public synchronized void startNewTurn() {
        currentTurnModifiedFiles.clear();
        pendingNewTurnClear = true;
    }

    /**
     * Explicitly clears all turn snapshots and modified file tracking.
     */
    public synchronized void clearTurnSnapshots() {
        activeTurnSnapshots.clear();
        currentTurnModifiedFiles.clear();
        pendingNewTurnClear = false;
    }

    /**
     * Record an edit that has been applied to disk.
     */
    public synchronized void recordAppliedEdit(EditProposal proposal, Path validatedPath, boolean existedBefore) {
        if (proposal == null) return;
        if (pendingNewTurnClear) {
            activeTurnSnapshots.clear();
            pendingNewTurnClear = false;
        }
        String filePath = proposal.getFilePath();
        currentTurnModifiedFiles.add(filePath);

        String resolved = validatedPath != null ? validatedPath.toAbsolutePath().toString() : filePath;
        if (validatedPath != null) {
            com.mkpro.tools.SymbolIndexTool.invalidate(validatedPath);
        } else {
            com.mkpro.tools.SymbolIndexTool.invalidate(Paths.get(filePath));
        }
        com.mkpro.infra.mq.JobScheduleManager.getInstance().notifyFileEdited(proposal.getFilePath());
        TurnEditRecord record = activeTurnSnapshots.get(filePath);
        if (record == null) {
            String orig = proposal.getOldContent() != null ? proposal.getOldContent() : "";
            List<EditProposal.DiffLine> diff = new EditProposal(
                "turn-" + filePath, filePath, orig, proposal.getNewContent(), !existedBefore
            ).getDiffLines();
            record = new TurnEditRecord(
                filePath, resolved, orig, proposal.getNewContent(),
                existedBefore, System.currentTimeMillis(), diff
            );
            activeTurnSnapshots.put(filePath, record);
        } else {
            record.currentContent = proposal.getNewContent() != null ? proposal.getNewContent() : "";
            record.timestamp = System.currentTimeMillis();
            record.cumulativeDiff = new EditProposal(
                "turn-" + filePath, filePath, record.originalContent, record.currentContent, !record.existedBeforeFirstEdit
            ).getDiffLines();
        }
    }

    /**
     * Get a copy of the active turn snapshots.
     */
    public synchronized Map<String, TurnEditRecord> getTurnSnapshots() {
        return new LinkedHashMap<>(activeTurnSnapshots);
    }

    /**
     * Get the set of files modified in the current turn.
     */
    public Set<String> getCurrentTurnModifiedFiles() {
        return new LinkedHashSet<>(currentTurnModifiedFiles);
    }

    /**
     * Clear the current turn's modified files set.
     */
    public void clearTurnModifiedFiles() {
        currentTurnModifiedFiles.clear();
    }

    /**
     * Revert a single file to its pre-turn state.
     */
    public synchronized boolean revertFile(String filePath) {
        if (filePath == null || filePath.isBlank()) return false;
        String matchedKey = null;
        TurnEditRecord record = activeTurnSnapshots.get(filePath);
        if (record != null) {
            matchedKey = filePath;
        } else {
            String normTarget = filePath.replace('\\', '/');
            for (Map.Entry<String, TurnEditRecord> entry : activeTurnSnapshots.entrySet()) {
                String kNorm = entry.getKey().replace('\\', '/');
                String rNorm = entry.getValue().resolvedPath.replace('\\', '/');
                if (kNorm.equals(normTarget) || rNorm.equals(normTarget) || rNorm.endsWith("/" + normTarget)) {
                    matchedKey = entry.getKey();
                    record = entry.getValue();
                    break;
                }
            }
        }
        if (record == null || matchedKey == null) {
            return false;
        }

        try {
            Path target = Paths.get(record.resolvedPath);
            if (record.existedBeforeFirstEdit) {
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.writeString(target, record.originalContent != null ? record.originalContent : "",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                Files.deleteIfExists(target);
            }
            com.mkpro.tools.SymbolIndexTool.invalidate(target);
            activeTurnSnapshots.remove(matchedKey);
            currentTurnModifiedFiles.remove(matchedKey);
            if (MkProEventBus.INSTANCE != null) {
                MkProEventBus.INSTANCE.emit(MkProEvent.system("Reverted file to pre-turn state: " + matchedKey));
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Revert all files modified in the active turn snapshot back to their pre-turn state.
     */
    public synchronized List<String> revertTurn() {
        List<String> reverted = new ArrayList<>();
        List<String> keys = new ArrayList<>(activeTurnSnapshots.keySet());
        for (String key : keys) {
            TurnEditRecord record = activeTurnSnapshots.get(key);
            if (record == null) continue;
            try {
                Path target = Paths.get(record.resolvedPath);
                if (record.existedBeforeFirstEdit) {
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.writeString(target, record.originalContent != null ? record.originalContent : "",
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } else {
                    Files.deleteIfExists(target);
                }
                com.mkpro.tools.SymbolIndexTool.invalidate(target);
                reverted.add(key);
            } catch (IOException ignored) {
            }
        }
        activeTurnSnapshots.clear();
        currentTurnModifiedFiles.clear();
        pendingNewTurnClear = false;
        if (MkProEventBus.INSTANCE != null && !reverted.isEmpty()) {
            MkProEventBus.INSTANCE.emit(MkProEvent.system("Reverted all " + reverted.size() + " file(s) from turn."));
        }
        return reverted;
    }

    /**
     * Submit a new edit proposal. Returns a Future that resolves to true (approved) or false (rejected).
     */
    public CompletableFuture<Boolean> submitProposal(EditProposal proposal) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingEdits.put(proposal.getId(), new PendingEdit(proposal, future));
        return future;
    }

    /**
     * Approve a pending edit by ID.
     * @return true if the proposal existed and was approved, false if not found/already resolved.
     */
    public boolean approve(String proposalId) {
        PendingEdit pending = pendingEdits.remove(proposalId);
        if (pending != null) {
            pending.future.complete(true);
            return true;
        }
        return false;
    }

    /**
     * Reject a pending edit by ID.
     * @return true if the proposal existed and was rejected, false if not found/already resolved.
     */
    public boolean reject(String proposalId) {
        PendingEdit pending = pendingEdits.remove(proposalId);
        if (pending != null) {
            pending.future.complete(false);
            return true;
        }
        return false;
    }

    /**
     * Get a pending proposal by ID.
     */
    public EditProposal getProposal(String proposalId) {
        PendingEdit pending = pendingEdits.get(proposalId);
        return pending != null ? pending.proposal : null;
    }

    /**
     * Get all pending proposals (for /api/edit/pending).
     */
    public Map<String, EditProposal> getPendingProposals() {
        Map<String, EditProposal> result = new ConcurrentHashMap<>();
        pendingEdits.forEach((id, pe) -> result.put(id, pe.proposal));
        return result;
    }

    /**
     * Check if there are any pending proposals.
     */
    public boolean hasPending() {
        return !pendingEdits.isEmpty();
    }

    private static class PendingEdit {
        final EditProposal proposal;
        final CompletableFuture<Boolean> future;

        PendingEdit(EditProposal proposal, CompletableFuture<Boolean> future) {
            this.proposal = proposal;
            this.future = future;
        }
    }
}
