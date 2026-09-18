package com.mkpro.knowledge;

import java.util.List;
import java.util.StringJoiner;

/**
 * Configuration for a knowledge topic loaded from schedules.yaml.
 */
public class TopicConfig {
    private String name;
    private String title;
    private List<String> sources; // URLs to fetch
    private String cron; // cron expression (simplified: interval in minutes)
    private String agent; // which agent analyzes (default: Coordinator)
    private String instruction; // what to look for / how to analyze
    private int refreshIntervalMinutes; // alternative to cron, simple interval

    public TopicConfig() {
        this.agent = "Coordinator";
        this.refreshIntervalMinutes = 60;
    }

    public TopicConfig(String name, String title) {
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

    public List<String> getSources() {
        return sources;
    }

    public String getCron() {
        return cron;
    }

    public String getAgent() {
        return agent;
    }

    public String getInstruction() {
        return instruction;
    }

    public int getRefreshIntervalMinutes() {
        return refreshIntervalMinutes;
    }

    // --- Setters ---

    public void setName(String name) {
        this.name = name;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setSources(List<String> sources) {
        this.sources = sources;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public void setAgent(String agent) {
        this.agent = agent;
    }

    public void setInstruction(String instruction) {
        this.instruction = instruction;
    }

    public void setRefreshIntervalMinutes(int refreshIntervalMinutes) {
        this.refreshIntervalMinutes = refreshIntervalMinutes;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", TopicConfig.class.getSimpleName() + "[", "]")
                .add("name='" + name + "'")
                .add("title='" + title + "'")
                .add("sources=" + sources)
                .add("cron='" + cron + "'")
                .add("agent='" + agent + "'")
                .add("instruction='" + instruction + "'")
                .add("refreshIntervalMinutes=" + refreshIntervalMinutes)
                .toString();
    }
}
