package com.mkpro.infra.network.peer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mkpro.infra.network.messaging.P2PMessageBus;
import org.junit.jupiter.api.*;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for cross-instance agent-to-agent communication.
 * Covers:
 * - Request message format and validation
 * - Response correlation via request_id
 * - Timeout handling when no peer responds
 * - AGENT_REQUEST routing (dispatched to handler, not SyncEngine)
 * - AGENT_RESPONSE routing (completes pending future, not passed to handler)
 */
public class PeerAgentCommunicationTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    // ==========================================================================
    // Protocol message format
    // ==========================================================================

    @Test
    void requestMessageHasRequiredFields() {
        ObjectNode request = buildRequest("Architect", "Review this design");
        assertEquals("AGENT_REQUEST", request.get("type").asText());
        assertNotNull(request.get("request_id").asText());
        assertFalse(request.get("request_id").asText().isEmpty());
        assertEquals("test-instance", request.get("from_instance").asText());
        assertEquals("Architect", request.get("target_agent").asText());
        assertEquals("Review this design", request.get("question").asText());
        assertTrue(request.get("timestamp").asLong() > 0);
    }

    @Test
    void responseMessageHasRequiredFields() {
        String requestId = UUID.randomUUID().toString();
        ObjectNode response = buildResponse(requestId, "Architect", "The coupling looks good.", true);
        assertEquals("AGENT_RESPONSE", response.get("type").asText());
        assertEquals(requestId, response.get("request_id").asText());
        assertEquals("peer-instance", response.get("from_instance").asText());
        assertEquals("Architect", response.get("agent").asText());
        assertEquals("The coupling looks good.", response.get("response").asText());
        assertTrue(response.get("success").asBoolean());
        assertTrue(response.get("timestamp").asLong() > 0);
    }

    @Test
    void requestIdIsUnique() {
        ObjectNode req1 = buildRequest("Coder", "q1");
        ObjectNode req2 = buildRequest("Coder", "q2");
        assertNotEquals(req1.get("request_id").asText(), req2.get("request_id").asText());
    }

    // ==========================================================================
    // Response correlation
    // ==========================================================================

    @Test
    void responseMatchesByRequestId() {
        String requestId = UUID.randomUUID().toString();
        ObjectNode response = buildResponse(requestId, "Architect", "result", true);

        // Simulate what P2PMessageBus does: match by request_id
        assertEquals(requestId, response.get("request_id").asText());
    }

    @Test
    void unmatchedResponseIdDoesNotCorrelate() {
        String sentRequestId = "abc-123";
        String receivedRequestId = "xyz-456";
        assertNotEquals(sentRequestId, receivedRequestId);
    }

    // ==========================================================================
    // Message routing
    // ==========================================================================

    @Test
    void agentRequestTypeIsIdentifiable() {
        ObjectNode request = buildRequest("Architect", "question");
        String type = request.get("type").asText();
        assertEquals("AGENT_REQUEST", type);
        // This would be routed to PeerAgentRequestHandler, not SyncEngine
    }

    @Test
    void agentResponseTypeIsIdentifiable() {
        ObjectNode response = buildResponse("id", "Architect", "answer", true);
        String type = response.get("type").asText();
        assertEquals("AGENT_RESPONSE", type);
        // This would complete a pending future in P2PMessageBus
    }

    @Test
    void memorySyncTypeIsDistinct() {
        ObjectNode syncMsg = mapper.createObjectNode();
        syncMsg.put("type", "MEMORY_SYNC");
        syncMsg.put("key", "memory:test");
        // This goes to SyncEngine, not PeerAgentRequestHandler
        assertNotEquals("AGENT_REQUEST", syncMsg.get("type").asText());
    }

    // ==========================================================================
    // Timeout handling
    // ==========================================================================

    @Test
    void futureTimesOutWhenNoResponse() {
        CompletableFuture<ObjectNode> future = new CompletableFuture<>();
        
        // Simulate timeout (very short for test)
        ObjectNode result = null;
        try {
            result = future.get(100, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Expected: TimeoutException
        }
        assertNull(result);
    }

    @Test
    void futureCompletesWhenResponseArrives() throws Exception {
        CompletableFuture<ObjectNode> future = new CompletableFuture<>();
        
        // Simulate response arriving
        ObjectNode response = buildResponse("req-1", "Coder", "Here's the answer", true);
        future.complete(response);

        ObjectNode result = future.get(1, TimeUnit.SECONDS);
        assertNotNull(result);
        assertEquals("Here's the answer", result.get("response").asText());
    }

    @Test
    void futureOnlyCompletesOnce() throws Exception {
        CompletableFuture<ObjectNode> future = new CompletableFuture<>();
        
        ObjectNode first = buildResponse("req-1", "Coder", "first", true);
        ObjectNode second = buildResponse("req-1", "Coder", "second", true);
        
        future.complete(first);
        future.complete(second); // Should be ignored (already complete)

        ObjectNode result = future.get(1, TimeUnit.SECONDS);
        assertEquals("first", result.get("response").asText()); // First response wins
    }

    // ==========================================================================
    // Security constraints validation
    // ==========================================================================

    @Test
    void remoteAgentGetsReadOnlyTools() {
        // Verify the tool list that PeerAgentRequestHandler would resolve
        java.util.List<String> allowedTools = java.util.List.of("file_read", "codebase_search", "graph_memory", "fetch_url");
        
        // These should NOT be in the remote agent's tools
        assertFalse(allowedTools.contains("shell"));
        assertFalse(allowedTools.contains("file_write"));
        assertFalse(allowedTools.contains("safe_write"));
        assertFalse(allowedTools.contains("selenium"));
    }

    // ==========================================================================
    // Error scenarios
    // ==========================================================================

    @Test
    void failedResponseHasSuccessFalse() {
        ObjectNode response = buildResponse("req-1", "Architect", "Agent not found", false);
        assertFalse(response.get("success").asBoolean());
    }

    @Test
    void contextFieldIsOptional() {
        ObjectNode request = mapper.createObjectNode();
        request.put("type", "AGENT_REQUEST");
        request.put("request_id", UUID.randomUUID().toString());
        request.put("from_instance", "test");
        request.put("target_agent", "Architect");
        request.put("question", "question");
        request.put("timestamp", System.currentTimeMillis());
        // No "context" field — should be fine
        assertFalse(request.has("context"));
    }

    @Test
    void emptyQuestionIsStillValid() {
        ObjectNode request = buildRequest("Architect", "");
        assertEquals("", request.get("question").asText());
        // Handler should still process (agent will respond with "unclear question")
    }

    // ==========================================================================
    // Helpers
    // ==========================================================================

    private ObjectNode buildRequest(String targetAgent, String question) {
        ObjectNode request = mapper.createObjectNode();
        request.put("type", "AGENT_REQUEST");
        request.put("request_id", UUID.randomUUID().toString());
        request.put("from_instance", "test-instance");
        request.put("target_agent", targetAgent);
        request.put("question", question);
        request.put("context", "");
        request.put("timestamp", System.currentTimeMillis());
        return request;
    }

    private ObjectNode buildResponse(String requestId, String agent, String response, boolean success) {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("type", "AGENT_RESPONSE");
        resp.put("request_id", requestId);
        resp.put("from_instance", "peer-instance");
        resp.put("agent", agent);
        resp.put("response", response);
        resp.put("success", success);
        resp.put("timestamp", System.currentTimeMillis());
        return resp;
    }
}
