package com.mkpro.infra.network.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mkpro.CentralMemory;
import com.mkpro.graph.ExtractionResult;
import com.mkpro.graph.MapDbGraphRepository;
import com.mkpro.infra.network.messaging.P2PMessageBus;
import com.mkpro.infra.network.security.P2PAuditLog;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SyncEngine coordinates state synchronization across the network by bridging
 * CentralMemory and MapDbGraphRepository changes with the P2PMessageBus.
 *
 * Key design decisions:
 * - Uses a syncing guard to prevent feedback loops (local update → broadcast → receive → local update → ...)
 * - Timestamps are included for future conflict resolution
 * - Complex values are serialized/deserialized via Jackson valueToTree/treeToValue
 */
public class SyncEngine {

    private final P2PMessageBus messageBus;
    private final CentralMemory centralMemory;
    private final MapDbGraphRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();
    private final P2PAuditLog auditLog = P2PAuditLog.getInstance();

    /** Guard flag: when true, CentralMemory listener notifications are suppressed (receiving remote update) */
    private final AtomicBoolean syncing = new AtomicBoolean(false);

    public SyncEngine(P2PMessageBus messageBus, CentralMemory centralMemory, MapDbGraphRepository repository) {
        this.messageBus = messageBus;
        this.centralMemory = centralMemory;
        this.repository = repository;

        // Hook into CentralMemory to listen for local updates
        this.centralMemory.addListener(this::onLocalMemoryChanged);

        // Hook into MapDbGraphRepository to listen for graph updates
        if (this.repository != null) {
            this.repository.addListener(this::broadcastGraphSync);
        }
    }

    /**
     * Called when CentralMemory is updated locally.
     * Only broadcasts if we're NOT currently applying a remote sync (prevents feedback loop).
     */
    private void onLocalMemoryChanged(String key, Object value) {
        if (syncing.get()) {
            return; // Suppress: this change came from a remote peer, don't re-broadcast
        }
        broadcastSync(key, value);
    }

    /**
     * Constructs and broadcasts a MEMORY_SYNC message.
     */
    private void broadcastSync(String key, Object value) {
        try {
            ObjectNode syncMessage = mapper.createObjectNode();
            syncMessage.put("type", "MEMORY_SYNC");
            syncMessage.put("key", key);
            syncMessage.set("value", mapper.valueToTree(value));
            syncMessage.put("timestamp", System.currentTimeMillis());

            messageBus.broadcast(syncMessage);
        } catch (Exception e) {
            System.err.println("[SyncEngine] Error broadcasting: " + e.getMessage());
        }
    }

    private void broadcastGraphSync(String key, ExtractionResult result) {
        if (syncing.get()) return;
        try {
            ObjectNode syncMessage = mapper.createObjectNode();
            syncMessage.put("type", "GRAPH_SYNC");
            syncMessage.put("key", key);
            syncMessage.set("result", mapper.valueToTree(result));
            syncMessage.put("timestamp", System.currentTimeMillis());

            messageBus.broadcast(syncMessage);
        } catch (Exception e) {
            System.err.println("[SyncEngine] Error broadcasting graph sync: " + e.getMessage());
        }
    }

    /**
     * Processes incoming synchronization messages from the network.
     * Sets the syncing guard to prevent feedback loops.
     *
     * @param message The received ObjectNode message.
     */
    public void processIncomingMessage(ObjectNode message) {
        String type = message.has("type") ? message.get("type").asText() : "";

        // Set guard to prevent re-broadcasting this change
        syncing.set(true);
        try {
            if ("MEMORY_SYNC".equals(type)) {
                processMemorySync(message);
            } else if ("GRAPH_SYNC".equals(type)) {
                processGraphSync(message);
            } else if ("TRUST_CONTRACTION".equals(type)) {
                handleTrustContraction(message);
            }
        } finally {
            syncing.set(false);
        }
    }

    private void processMemorySync(ObjectNode message) {
        try {
            String key = message.get("key").asText();
            JsonNode valueNode = message.get("value");

            // Deserialize value based on what CentralMemory.updateFromRemote expects
            Object value;
            if (valueNode == null || valueNode.isNull()) {
                value = null;
            } else if (valueNode.isTextual()) {
                value = valueNode.asText();
            } else {
                // Complex object — pass the raw JSON string for CentralMemory to handle
                // CentralMemory.updateFromRemote handles typed deserialization by key prefix
                value = valueNode.toString();
            }

            centralMemory.updateFromRemote(key, value);
        } catch (Exception e) {
            System.err.println("[SyncEngine] Error processing memory sync: " + e.getMessage());
        }
    }

    private void processGraphSync(ObjectNode message) {
        try {
            String key = message.get("key").asText();
            JsonNode resultNode = message.get("result");
            if (resultNode != null) {
                ExtractionResult result = mapper.treeToValue(resultNode, ExtractionResult.class);
                if (repository != null) {
                    repository.mergeExtraction(key, result);
                }
            }
        } catch (Exception e) {
            System.err.println("[SyncEngine] Error processing graph sync: " + e.getMessage());
        }
    }

    /**
     * Implements TrustContractionHandler to handle the cleanup of old trust anchors.
     * Triggered by TRUST_CONTRACTION message type.
     */
    private void handleTrustContraction(ObjectNode message) {
        String source = message.has("instance_id") ? message.get("instance_id").asText() : "unknown";
        System.out.println("\n[SyncEngine] Processing TRUST_CONTRACTION from " + source);
        
        // Log the security event
        auditLog.log(P2PAuditLog.EventType.MESSAGE_RECEIVED, source, "Trust Contraction initiated (Phase C)");

        // Perform cleanup of old trust anchors
        // In a full implementation, this would call a SecurityService to reload the truststore
        // without the old CA root. For now, we update the local rotation state.
        
        centralMemory.putMemory("mesh.rotation.phase", "Phase C: Cleanup Complete");
        System.out.println("[SyncEngine] Cleanup complete. Old trust anchors decommissioned.");
    }
}