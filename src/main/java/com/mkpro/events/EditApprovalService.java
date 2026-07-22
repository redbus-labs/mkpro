package com.mkpro.events;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages pending edit proposals and their approval/rejection.
 * 
 * Flow:
 * 1. safe_write_file creates proposal → submitProposal() returns a Future
 * 2. Sinks display the proposal (terminal: auto-approve timer, web: diff viewer)
 * 3. Any channel calls approve(id) or reject(id) → completes the Future
 * 4. safe_write_file unblocks and acts accordingly
 */
public class EditApprovalService {

    /** Singleton instance — set during bootstrap, accessed by safe_write_file tool */
    public static volatile EditApprovalService INSTANCE;

    private final Map<String, PendingEdit> pendingEdits = new ConcurrentHashMap<>();

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
