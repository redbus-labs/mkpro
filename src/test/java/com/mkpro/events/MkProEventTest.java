package com.mkpro.events;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MkProEvent factory methods and data access.
 */
public class MkProEventTest {

    @Test
    void routingEvent() {
        MkProEvent event = MkProEvent.routing("DevOps", "85", "DEVOPS");
        assertEquals(MkProEvent.Type.ROUTING_DECISION, event.getType());
        assertEquals("DevOps", event.get("agent"));
        assertEquals("85", event.get("confidence"));
        assertEquals("DEVOPS", event.get("category"));
        assertTrue(event.getTimestamp() > 0);
    }

    @Test
    void routingBelowEvent() {
        MkProEvent event = MkProEvent.routingBelow("Coordinator", "30");
        assertEquals(MkProEvent.Type.ROUTING_BELOW, event.getType());
        assertEquals("Coordinator", event.get("agent"));
        assertEquals("30", event.get("confidence"));
    }

    @Test
    void routingInactiveEvent() {
        MkProEvent event = MkProEvent.routingInactive("15");
        assertEquals(MkProEvent.Type.ROUTING_INACTIVE, event.getType());
        assertEquals("15", event.get("observations"));
    }

    @Test
    void routingKeywordsEvent() {
        MkProEvent event = MkProEvent.routingKeywords("SecurityAuditor");
        assertEquals(MkProEvent.Type.ROUTING_KEYWORDS, event.getType());
        assertEquals("SecurityAuditor", event.get("agent"));
    }

    @Test
    void makerThoughtEvent() {
        MkProEvent event = MkProEvent.makerThought("CONTINUE", "Progress OK");
        assertEquals(MkProEvent.Type.MAKER_THOUGHT, event.getType());
        assertEquals("CONTINUE", event.get("action"));
        assertEquals("Progress OK", event.get("reason"));
    }

    @Test
    void makerGoalEvent() {
        MkProEvent event = MkProEvent.makerGoal("implement auth");
        assertEquals(MkProEvent.Type.MAKER_GOAL, event.getType());
        assertEquals("implement auth", event.get("goal"));
    }

    @Test
    void makerCompleteEvent() {
        MkProEvent event = MkProEvent.makerComplete("implement auth (3 turns)");
        assertEquals(MkProEvent.Type.MAKER_COMPLETE, event.getType());
        assertEquals("implement auth (3 turns)", event.get("goal"));
    }

    @Test
    void systemEvent() {
        MkProEvent event = MkProEvent.system("Knowledge acquired: k8s-hpa");
        assertEquals(MkProEvent.Type.SYSTEM, event.getType());
        assertEquals("Knowledge acquired: k8s-hpa", event.get("message"));
    }

    @Test
    void streamStartEvent() {
        MkProEvent event = MkProEvent.streamStart("Coder", "gemini-2.0-flash");
        assertEquals(MkProEvent.Type.STREAM_START, event.getType());
        assertEquals("Coder", event.get("agent"));
        assertEquals("gemini-2.0-flash", event.get("model"));
    }

    @Test
    void streamStartNullModel() {
        MkProEvent event = MkProEvent.streamStart("Coder", null);
        assertEquals("", event.get("model"));
    }

    @Test
    void streamChunkEvent() {
        MkProEvent event = MkProEvent.streamChunk("Hello world");
        assertEquals(MkProEvent.Type.STREAM_CHUNK, event.getType());
        assertEquals("Hello world", event.get("text"));
    }

    @Test
    void streamEndEvent() {
        MkProEvent event = MkProEvent.streamEnd();
        assertEquals(MkProEvent.Type.STREAM_END, event.getType());
    }

    @Test
    void delegationEvent() {
        MkProEvent event = MkProEvent.delegation("Tester");
        assertEquals(MkProEvent.Type.DELEGATION, event.getType());
        assertEquals("Tester", event.get("agent"));
    }

    @Test
    void editProposalEvent() {
        EditProposal proposal = new EditProposal("abc123", "src/Main.java", "old code", "new code");
        MkProEvent event = MkProEvent.editProposal(proposal);
        assertEquals(MkProEvent.Type.EDIT_PROPOSAL, event.getType());
        assertEquals("abc123", event.get("id"));
        assertEquals("src/Main.java", event.get("path"));
        assertSame(proposal, event.getEditProposal());
    }

    @Test
    void editApprovedEvent() {
        MkProEvent event = MkProEvent.editApproved("abc123", "src/Main.java");
        assertEquals(MkProEvent.Type.EDIT_APPROVED, event.getType());
        assertEquals("abc123", event.get("id"));
        assertEquals("src/Main.java", event.get("path"));
    }

    @Test
    void editRejectedEvent() {
        MkProEvent event = MkProEvent.editRejected("abc123", "src/Main.java");
        assertEquals(MkProEvent.Type.EDIT_REJECTED, event.getType());
        assertEquals("abc123", event.get("id"));
    }

    @Test
    void getWithDefault() {
        MkProEvent event = MkProEvent.system("test");
        assertEquals("", event.get("nonexistent"));
        assertEquals("fallback", event.get("nonexistent", "fallback"));
    }

    @Test
    void dataIsImmutableView() {
        MkProEvent event = MkProEvent.system("test");
        assertNotNull(event.getData());
        assertEquals("test", event.getData().get("message"));
    }
}
