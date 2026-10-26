package com.mkpro.models.notes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public class NoteItem implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonProperty("id")
    private String id;

    @JsonProperty("title")
    private String title;

    @JsonProperty("content")
    private String content;

    @JsonProperty("tags")
    private List<String> tags = new ArrayList<>();

    @JsonProperty("pinned")
    private boolean pinned = false;

    @JsonProperty("projectPath")
    private String projectPath;

    @JsonProperty("projectScoped")
    private boolean projectScoped = true;

    @JsonProperty("createdAt")
    private long createdAt;

    @JsonProperty("updatedAt")
    private long updatedAt;

    public NoteItem() {
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public NoteItem(String id, String title, String content) {
        this();
        this.id = id;
        this.title = title;
        this.content = content;
    }

    public NoteItem(String id, String title, String content, boolean projectScoped) {
        this(id, title, content);
        this.projectScoped = projectScoped;
        if (!projectScoped) {
            this.projectPath = "global";
        }
    }

    public NoteItem(String id, String title, String content, String projectPath) {
        this(id, title, content);
        this.projectPath = projectPath;
        this.projectScoped = projectPath != null && !projectPath.isBlank() && !"global".equalsIgnoreCase(projectPath);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags != null ? tags : new ArrayList<>(); }

    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }

    public String getProjectPath() { return projectPath; }
    public void setProjectPath(String projectPath) { this.projectPath = projectPath; }

    public boolean isProjectScoped() { return projectScoped; }
    public void setProjectScoped(boolean projectScoped) { this.projectScoped = projectScoped; }

    @JsonProperty("projectName")
    public String getProjectName() {
        if (projectPath == null || projectPath.isBlank() || "global".equalsIgnoreCase(projectPath)) {
            return "Global";
        }
        try {
            Path fileName = Path.of(projectPath).getFileName();
            return fileName != null ? fileName.toString() : projectPath;
        } catch (Exception e) {
            return projectPath;
        }
    }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NoteItem noteItem = (NoteItem) o;
        return Objects.equals(id, noteItem.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "NoteItem{" +
                "id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", pinned=" + pinned +
                ", projectPath='" + projectPath + '\'' +
                ", projectScoped=" + projectScoped +
                '}';
    }
}
