package com.mkpro.events;

import org.junit.jupiter.api.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EditApprovalService — proposal lifecycle, approve, reject, pending management.
 */
public class EditApprovalServiceTest {

    private EditApprovalService service;

    @BeforeEach
    void setUp() {
        service = new EditApprovalService();
    }

    @Test
    void initiallyNoPending() {
        assertFalse(service.hasPending());
        assertTrue(service.getPendingProposals().isEmpty());
    }

    @Test
    void submitCreatesProposal() {
        EditProposal proposal = new EditProposal("id1", "src/Main.java", "old", "new");
        CompletableFuture<Boolean> future = service.submitProposal(proposal);

        assertTrue(service.hasPending());
        assertNotNull(service.getProposal("id1"));
        assertFalse(future.isDone());
    }

    @Test
    void approveResolvesToTrue() throws Exception {
        EditProposal proposal = new EditProposal("id2", "file.txt", "a", "b");
        CompletableFuture<Boolean> future = service.submitProposal(proposal);

        boolean result = service.approve("id2");
        assertTrue(result);
        assertTrue(future.get(1, TimeUnit.SECONDS));
        assertFalse(service.hasPending());
    }

    @Test
    void rejectResolvesToFalse() throws Exception {
        EditProposal proposal = new EditProposal("id3", "file.txt", "a", "b");
        CompletableFuture<Boolean> future = service.submitProposal(proposal);

        boolean result = service.reject("id3");
        assertTrue(result);
        assertFalse(future.get(1, TimeUnit.SECONDS));
        assertFalse(service.hasPending());
    }

    @Test
    void approveNonExistentReturnsFalse() {
        assertFalse(service.approve("nonexistent"));
    }

    @Test
    void rejectNonExistentReturnsFalse() {
        assertFalse(service.reject("nonexistent"));
    }

    @Test
    void doubleApproveReturnsFalse() {
        EditProposal proposal = new EditProposal("id4", "file.txt", "a", "b");
        service.submitProposal(proposal);

        assertTrue(service.approve("id4"));
        assertFalse(service.approve("id4")); // Already resolved
    }

    @Test
    void multipleProposalsPending() {
        service.submitProposal(new EditProposal("a", "file1.txt", "", "new1"));
        service.submitProposal(new EditProposal("b", "file2.txt", "", "new2"));
        service.submitProposal(new EditProposal("c", "file3.txt", "", "new3"));

        assertTrue(service.hasPending());
        assertEquals(3, service.getPendingProposals().size());

        service.approve("b");
        assertEquals(2, service.getPendingProposals().size());
        assertNull(service.getProposal("b"));
        assertNotNull(service.getProposal("a"));
        assertNotNull(service.getProposal("c"));
    }

    @Test
    void getProposalReturnsCorrectData() {
        EditProposal proposal = new EditProposal("x", "src/App.java", "line1\nline2", "line1\nmodified");
        service.submitProposal(proposal);

        EditProposal retrieved = service.getProposal("x");
        assertNotNull(retrieved);
        assertEquals("x", retrieved.getId());
        assertEquals("src/App.java", retrieved.getFilePath());
        assertEquals("line1\nline2", retrieved.getOldContent());
        assertEquals("line1\nmodified", retrieved.getNewContent());
    }

    @Test
    void getProposalNullForUnknown() {
        assertNull(service.getProposal("unknown"));
    }

    @Test
    void proposalDiffComputed() {
        EditProposal proposal = new EditProposal("d", "test.txt", "hello\nworld", "hello\nEarth");
        assertFalse(proposal.getDiffLines().isEmpty());
        assertFalse(proposal.isNewFile());
    }

    @Test
    void newFileProposal() {
        EditProposal proposal = new EditProposal("e", "new.txt", null, "content here");
        assertTrue(proposal.isNewFile());
        assertFalse(proposal.getDiffLines().isEmpty());
    }
}
