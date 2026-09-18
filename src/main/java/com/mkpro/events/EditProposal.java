package com.mkpro.events;

import java.util.List;
import java.util.ArrayList;

/**
 * Represents a proposed file edit awaiting user approval.
 * Contains the diff information and the path for display in any sink.
 */
public class EditProposal {

    private final String id;
    private final String filePath;
    private final String oldContent;
    private final String newContent;
    private final List<DiffLine> diffLines;
    private final long createdAt;
    private final boolean isNewFile;

    public EditProposal(String id, String filePath, String oldContent, String newContent) {
        this.id = id;
        this.filePath = filePath;
        this.oldContent = oldContent;
        this.newContent = newContent;
        this.createdAt = System.currentTimeMillis();
        this.isNewFile = (oldContent == null || oldContent.isEmpty());
        this.diffLines = computeDiff(oldContent, newContent);
    }

    public String getId() { return id; }
    public String getFilePath() { return filePath; }
    public String getOldContent() { return oldContent; }
    public String getNewContent() { return newContent; }
    public List<DiffLine> getDiffLines() { return diffLines; }
    public long getCreatedAt() { return createdAt; }
    public boolean isNewFile() { return isNewFile; }

    /**
     * Compute simple line-by-line diff.
     */
    private List<DiffLine> computeDiff(String oldText, String newText) {
        List<DiffLine> lines = new ArrayList<>();
        String[] oldLines = (oldText != null ? oldText : "").split("\n", -1);
        String[] newLines = (newText != null ? newText : "").split("\n", -1);

        int maxLen = Math.max(oldLines.length, newLines.length);

        // Cap display at 100 diff lines for large files
        int changesShown = 0;
        int maxChanges = 100;

        for (int i = 0; i < maxLen && changesShown < maxChanges; i++) {
            String oldL = (i < oldLines.length) ? oldLines[i] : null;
            String newL = (i < newLines.length) ? newLines[i] : null;

            if (oldL == null && newL != null) {
                lines.add(new DiffLine(DiffLine.Type.ADDED, newL, i + 1));
                changesShown++;
            } else if (oldL != null && newL == null) {
                lines.add(new DiffLine(DiffLine.Type.REMOVED, oldL, i + 1));
                changesShown++;
            } else if (oldL != null && !oldL.equals(newL)) {
                lines.add(new DiffLine(DiffLine.Type.REMOVED, oldL, i + 1));
                lines.add(new DiffLine(DiffLine.Type.ADDED, newL, i + 1));
                changesShown++;
            }
            // Skip unchanged lines (don't include in diff)
        }

        if (changesShown >= maxChanges) {
            lines.add(new DiffLine(DiffLine.Type.INFO, "... (diff truncated, " + maxLen + " total lines)", 0));
        }

        return lines;
    }

    public static class DiffLine {
        public enum Type { ADDED, REMOVED, CONTEXT, INFO }

        private final Type type;
        private final String text;
        private final int lineNumber;

        public DiffLine(Type type, String text, int lineNumber) {
            this.type = type;
            this.text = text;
            this.lineNumber = lineNumber;
        }

        public Type getType() { return type; }
        public String getText() { return text; }
        public int getLineNumber() { return lineNumber; }
    }
}
