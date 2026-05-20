package com.mkpro.infra.network.sync;

import com.mkpro.CentralMemory;
import com.mkpro.infra.network.messaging.P2PMessageBus;
import org.json.JSONObject;

/**
 * SyncEngine coordinates state synchronization across the network by bridging
 * CentralMemory changes with the P2PMessageBus.
 */
public class SyncEngine {

    private final P2PMessageBus messageBus;
    private final CentralMemory centralMemory;

    public SyncEngine(P2PMessageBus messageBus, CentralMemory centralMemory) {
        this.messageBus = messageBus;
        this.centralMemory = centralMemory;

        // Hook into CentralMemory to listen for local updates
        // Note: CentralMemory needs to implement addListener and a functional interface for this to work.
        this.centralMemory.addListener(this::onLocalMemoryChanged);
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
        JSONObject syncMessage = new JSONObject();
        syncMessage.put("type", "MEMORY_SYNC");
        syncMessage.put("key", key);
        syncMessage.put("value", value);
        syncMessage.put("timestamp", System.currentTimeMillis());

        messageBus.broadcast(syncMessage);
    }

    /**
     * Processes incoming synchronization messages from the network.
     * Updates CentralMemory while ensuring no feedback loops occur.
     * 
     * @param message The received JSON message.
     */
    public void processIncomingMessage(JSONObject message) {
        if ("MEMORY_SYNC".equals(message.optString("type"))) {
            String key = message.getString("key");
            Object value = message.get("value");

            // Update CentralMemory. 
            // Note: CentralMemory.updateFromRemote should be used to prevent 
            // re-broadcasting the same change back to the network.
            centralMemory.updateFromRemote(key, value);
        }
    }
}
