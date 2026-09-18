package com.mkpro.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mkpro.CentralMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Stores and retrieves TopicReport objects using CentralMemory with a "knowledge:" key prefix.
 * Uses Jackson for JSON serialization. Handles nulls and parse errors gracefully.
 */
public class KnowledgeStore {

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeStore.class);
    private static final String KEY_PREFIX = "knowledge:";

    private final CentralMemory centralMemory;
    private final ObjectMapper objectMapper;

    public KnowledgeStore(CentralMemory centralMemory) {
        this.centralMemory = centralMemory;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Serializes the given TopicReport to JSON and saves it in CentralMemory
     * under the key "knowledge:{report.getName()}".
     */
    public void saveReport(TopicReport report) {
        if (report == null || report.getName() == null || report.getName().isBlank()) {
            LOG.warn("Cannot save report: report or report name is null/blank");
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(report);
            String key = KEY_PREFIX + report.getName();
            centralMemory.saveMemory(key, json);
        } catch (Exception e) {
            LOG.warn("Failed to serialize TopicReport '{}': {}", report.getName(), e.getMessage());
        }
    }

    /**
     * Retrieves a TopicReport by topic name from CentralMemory.
     *
     * @param topicName the topic name (without prefix)
     * @return the deserialized TopicReport, or null if not found or on parse error
     */
    public TopicReport getReport(String topicName) {
        if (topicName == null || topicName.isBlank()) {
            return null;
        }
        try {
            String key = KEY_PREFIX + topicName;
            String json = centralMemory.getMemory(key);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, TopicReport.class);
        } catch (Exception e) {
            LOG.warn("Failed to deserialize TopicReport '{}': {}", topicName, e.getMessage());
            return null;
        }
    }

    /**
     * Retrieves all TopicReports stored in CentralMemory.
     * Filters entries by the "knowledge:" prefix and skips any that are
     * empty (deleted markers) or fail to parse.
     *
     * @return list of all valid TopicReports, or empty list if none found
     */
    public List<TopicReport> getAllReports() {
        try {
            Map<String, String> allMemories = centralMemory.getAllMemories();
            if (allMemories == null || allMemories.isEmpty()) {
                return Collections.emptyList();
            }
            List<TopicReport> reports = new ArrayList<>();
            for (Map.Entry<String, String> entry : allMemories.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key.startsWith(KEY_PREFIX) && value != null && !value.isBlank()) {
                    try {
                        TopicReport report = objectMapper.readValue(value, TopicReport.class);
                        reports.add(report);
                    } catch (Exception e) {
                        LOG.warn("Failed to deserialize TopicReport for key '{}': {}", key, e.getMessage());
                    }
                }
            }
            return reports;
        } catch (Exception e) {
            LOG.warn("Failed to retrieve all TopicReports: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Deletes a TopicReport by saving an empty string as a deletion marker.
     * CentralMemory does not expose a delete method, so empty values are
     * treated as "deleted" and filtered out in getAllReports().
     *
     * @param topicName the topic name to delete (without prefix)
     */
    public void deleteReport(String topicName) {
        if (topicName == null || topicName.isBlank()) {
            LOG.warn("Cannot delete report: topic name is null/blank");
            return;
        }
        String key = KEY_PREFIX + topicName;
        centralMemory.saveMemory(key, "");
    }
}
