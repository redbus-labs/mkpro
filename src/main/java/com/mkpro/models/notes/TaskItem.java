package com.mkpro.models.notes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskItem implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonProperty("id")
    private String id;

    @JsonProperty("title")
    private String title;

    @JsonProperty("status")
    private String status = "TODO"; // "TODO", "IN_PROGRESS", "DONE"

    @JsonProperty("priority")
    private String priority = "medium"; // "low", "medium", "high"

    @JsonProperty("dueDate")
    private String dueDate;

    @JsonProperty("createdAt")
    private long createdAt;

    @JsonProperty("updatedAt")
    private long updatedAt;

    public TaskItem() {
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public TaskItem(String id, String title, String status, String priority) {
        this();
        this.id = id;
        this.title = title;
        if (status != null) this.status = status;
        if (priority != null) this.priority = priority;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }

    public String getDueDate() { return dueDate; }
    public void setDueDate(String dueDate) { this.dueDate = dueDate; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TaskItem taskItem = (TaskItem) o;
        return Objects.equals(id, taskItem.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "TaskItem{" +
                "id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", status='" + status + '\'' +
                ", priority='" + priority + '\'' +
                '}';
    }
}
