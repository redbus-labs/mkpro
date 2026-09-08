package com.mkpro.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * A topic report stored in CentralMemory. Evolves with each refresh cycle.
 * Serialized as JSON.
 */
public class TopicReport {
    private String name; // topic key
    private String title;
    private String summary; // current accumulated knowledge (evolves)
    private List<String> sources; // URLs that contributed
    private String lastUpdated; // ISO timestamp
    private List<String> keywords; // extracted for TF-IDF indexing
    private double confidence; // 0.0-1.0 how well-supported
    private List<HistoryEntry> history; // change log

    public TopicReport() {
        this.history = new ArrayList<>();
        this.keywords = new ArrayList<>();
        this.sources = new ArrayList<>();
        this.confidence = 0.5;
    }

    public TopicReport(String name, String title) {
        this();
        this.name = name;
        this.title = title;
    }

    // --- Getters ---

    public String getName() {
        return name;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public List<String> getSources() {
        return sources;
    }

    public String getLastUpdated() {
        return lastUpdated;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public double getConfidence() {
        return confidence;
    }

    public List<HistoryEntry> getHistory() {
        return history;
    }

    // --- Setters ---

    public void setName(String name) {
        this.name = name;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public void setSources(List<String> sources) {
        this.sources = sources;
    }

    public void setLastUpdated(String lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public void setHistory(List<HistoryEntry> history) {
        this.history = history;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", TopicReport.class.getSimpleName() + "[", "]")
                .add("name='" + name + "'")
                .add("title='" + title + "'")
                .add("summary='" + summary + "'")
                .add("sources=" + sources)
                .add("lastUpdated='" + lastUpdated + "'")
                .add("keywords=" + keywords)
                .add("confidence=" + confidence)
                .add("history=" + history)
                .toString();
    }

    /**
     * Represents a single history entry tracking changes to the topic report.
     */
    public static class HistoryEntry {
        private String date; // ISO timestamp of the change
        private String delta; // description of what changed

        public HistoryEntry() {
        }

        public HistoryEntry(String date, String delta) {
            this.date = date;
            this.delta = delta;
        }

        public String getDate() {
            return date;
        }

        public void setDate(String date) {
            this.date = date;
        }

        public String getDelta() {
            return delta;
        }

        public void setDelta(String delta) {
            this.delta = delta;
        }

        @Override
        public String toString() {
            return new StringJoiner(", ", HistoryEntry.class.getSimpleName() + "[", "]")
                    .add("date='" + date + "'")
                    .add("delta='" + delta + "'")
                    .toString();
        }
    }
}
