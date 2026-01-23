package com.mkpro.models;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Goal implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Status {
        PENDING, IN_PROGRESS, COMPLETED, FAILED
    }

    private String id;
    private String description;
    private Status status;
    private List<Goal> subGoals;
    private long createdAt;
    private long updatedAt;

    public Goal(String description) {
        this.id = UUID.randomUUID().toString();
        this.description = description;
        this.status = Status.PENDING;
        this.subGoals = new ArrayList<>();
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
    }

    // Getters
    public String getId() { return id; }
    public String getDescription() { return description; }
    public Status getStatus() { return status; }
    public List<Goal> getSubGoals() { return subGoals; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }

    // Setters
    public void setDescription(String description) {
        this.description = description;
        this.updatedAt = System.currentTimeMillis();
    }

    public void setStatus(Status status) {
        this.status = status;
        this.updatedAt = System.currentTimeMillis();
    }

    public void addSubGoal(Goal subGoal) {
        this.subGoals.add(subGoal);
        this.updatedAt = System.currentTimeMillis();
    }
    
    @Override
    public String toString() {
        return String.format("[%s] %s (ID: %s)", status, description, id);
    }
}
