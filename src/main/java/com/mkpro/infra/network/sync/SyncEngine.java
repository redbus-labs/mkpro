package com.mkpro.infra.network.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mkpro.CentralMemory;
import com.mkpro.graph.ExtractionResult;
import com.mkpro.graph.MapDbGraphRepository;
import com.mkpro.infra.network.messaging.P2PMessageBus;

/**
 * SyncEngine coordinates state synchronization across the network by bridging
 * CentralMemory and MapDbGraphRepository changes with the P2PMessageBus.
 */
public class SyncEngine {

    private final P2PMessageBus messageBus;
    private final CentralMemory centralMemory;
    private final MapDbGraphRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();

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
     * Broadcasts the change to the network.
     */
    private void onLocalMemoryChanged(String key, Object value) {
        broadcastSync(key, value);
    }

    /**
     * Constructs and broadcasts a MEMORY_SYNC message.
     */
    private void broadcastSync(String key, Object value) {
        ObjectNode syncMessage = mapper.createObjectNode();
        syncMessage.put("type", "MEMORY_SYNC");
        syncMessage.put("key", key);
        syncMessage.putPOJO("value", value);
        syncMessage.put("timestamp", System.currentTimeMillis());

        messageBus.broadcast(syncMessage);
    }

    private void broadcastGraphSync(String key, ExtractionResult result) {
        try {
            ObjectNode syncMessage = mapper.createObjectNode();
            syncMessage.put("type", "GRAPH_SYNC");
            syncMessage.put("key", key);
            syncMessage.set("result", mapper.valueToTree(result));
            syncMessage.put("timestamp", System.currentTimeMillis());

            messageBus.broadcast(syncMessage);
        } catch (Exception e) {
            System.err.println("Error broadcasting graph sync: " + e.getMessage());
        }
    }

    /**
     * Processes incoming synchronization messages from the network.
     * Updates CentralMemory while ensuring no feedback loops occur.
     * 
     * @param message The received ObjectNode message.
     */
    public void processIncomingMessage(ObjectNode message) {
        String type = message.has("type") ? message.get("type").asText() : "";
        if ("MEMORY_SYNC".equals(type)) {
            String key = message.get("key").asText();
            // For simple values, extract as text; for complex objects the consumer handles it
            Object value = message.has("value") ? message.get("value").asText() : null;

            // Update CentralMemory.
            // Note: CentralMemory.updateFromRemote should be used to prevent
            // re-broadcasting the same change back to the network.
            centralMemory.updateFromRemote(key, value);
        } else if ("GRAPH_SYNC".equals(type)) {
            try {
                String key = message.get("key").asText();
                String resultJson = message.get("result").toString();
                ExtractionResult result = mapper.readValue(resultJson, ExtractionResult.class);
                if (repository != null) {
                    repository.mergeExtraction(key, result);
                }
            } catch (Exception e) {
                System.err.println("Error processing incoming graph sync: " + e.getMessage());
            }
        }
    }
}
