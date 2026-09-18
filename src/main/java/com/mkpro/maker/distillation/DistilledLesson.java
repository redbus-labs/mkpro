package com.mkpro.maker.distillation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Data class representing a synthesized distillation lesson learned from a trajectory.
 */
public class DistilledLesson {
    private String lessonId;
    private String signature;
    private FailureCategory category;
    private String rootCauseSummary;
    private String failedHypothesis;
    private List<String> negativeConstraints;
    private String suggestedPivot;
    private long timestamp;
    private double confidence = 1.0;

    public DistilledLesson() {
        this.negativeConstraints = new ArrayList<>();
        this.timestamp = System.currentTimeMillis();
        this.confidence = 1.0;
    }

    public DistilledLesson(String signature, FailureCategory category, String negativeConstraint,
                           String suggestedPivot, double confidence) {
        this("lesson-" + System.nanoTime(), signature, category, negativeConstraint, null,
                (negativeConstraint != null ? List.of(negativeConstraint) : Collections.emptyList()),
                suggestedPivot, System.currentTimeMillis(), confidence);
    }

    public DistilledLesson(String signature, LessonCategory category, String negativeConstraint,
                           String suggestedPivot, double confidence) {
        this(signature, category != null ? category.toFailureCategory() : FailureCategory.UNKNOWN,
                negativeConstraint, suggestedPivot, confidence);
    }

    public DistilledLesson(String signature, FailureCategory category, String negativeConstraint,
                           String suggestedPivot) {
        this(signature, category, negativeConstraint, suggestedPivot, 1.0);
    }

    public DistilledLesson(String signature, LessonCategory category, String negativeConstraint,
                           String suggestedPivot) {
        this(signature, category, negativeConstraint, suggestedPivot, 1.0);
    }

    public DistilledLesson(String lessonId, FailureCategory category, String rootCauseSummary,
                           String failedHypothesis, List<String> negativeConstraints,
                           String suggestedPivot, long timestamp) {
        this(lessonId, null, category, rootCauseSummary, failedHypothesis, negativeConstraints, suggestedPivot, timestamp, 1.0);
    }

    public DistilledLesson(String lessonId, String signature, FailureCategory category, String rootCauseSummary,
                           String failedHypothesis, List<String> negativeConstraints,
                           String suggestedPivot, long timestamp) {
        this(lessonId, signature, category, rootCauseSummary, failedHypothesis, negativeConstraints, suggestedPivot, timestamp, 1.0);
    }

    public DistilledLesson(String lessonId, String signature, FailureCategory category, String rootCauseSummary,
                           String failedHypothesis, List<String> negativeConstraints,
                           String suggestedPivot, long timestamp, double confidence) {
        this.lessonId = lessonId;
        this.signature = signature;
        this.category = category;
        this.rootCauseSummary = rootCauseSummary;
        this.failedHypothesis = failedHypothesis;
        this.negativeConstraints = (negativeConstraints != null) ? new ArrayList<>(negativeConstraints) : new ArrayList<>();
        this.suggestedPivot = suggestedPivot;
        this.timestamp = timestamp;
        this.confidence = confidence;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Generates a clean XML prompt envelope for injection into downstream LLM prompts.
     */
    public String toPromptEnvelope() {
        StringBuilder sb = new StringBuilder();
        String idAttr = (lessonId != null && !lessonId.isEmpty()) ? " id=\"" + escapeXml(lessonId) + "\"" : "";
        sb.append("<distilled_lesson").append(idAttr).append(">\n");

        if (category != null) {
            sb.append("  <category>").append(escapeXml(category.name())).append("</category>\n");
        }
        if (rootCauseSummary != null && !rootCauseSummary.isEmpty()) {
            sb.append("  <root_cause>").append(escapeXml(rootCauseSummary)).append("</root_cause>\n");
        }
        if (failedHypothesis != null && !failedHypothesis.isEmpty()) {
            sb.append("  <failed_hypothesis>").append(escapeXml(failedHypothesis)).append("</failed_hypothesis>\n");
        }
        if (negativeConstraints != null && !negativeConstraints.isEmpty()) {
            sb.append("  <negative_constraints>\n");
            for (String constraint : negativeConstraints) {
                if (constraint != null && !constraint.trim().isEmpty()) {
                    sb.append("    <constraint>").append(escapeXml(constraint.trim())).append("</constraint>\n");
                }
            }
            sb.append("  </negative_constraints>\n");
        }
        if (suggestedPivot != null && !suggestedPivot.isEmpty()) {
            sb.append("  <suggested_pivot>").append(escapeXml(suggestedPivot)).append("</suggested_pivot>\n");
        }

        sb.append("</distilled_lesson>");
        return sb.toString();
    }

    private static String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }

    public String getLessonId() {
        return lessonId;
    }

    public void setLessonId(String lessonId) {
        this.lessonId = lessonId;
    }

    public String getSignature() {
        if (signature != null && !signature.isBlank()) {
            return signature.replaceAll("\\s+", " ").trim();
        }
        if (rootCauseSummary != null && !rootCauseSummary.isBlank()) {
            return rootCauseSummary.replaceAll("\\s+", " ").trim();
        }
        if (failedHypothesis != null && !failedHypothesis.isBlank()) {
            return failedHypothesis.replaceAll("\\s+", " ").trim();
        }
        return (lessonId != null ? lessonId : "unknown");
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public FailureCategory getCategory() {
        return category;
    }

    public LessonCategory getLessonCategory() {
        return LessonCategory.fromFailureCategory(this.category);
    }

    public FailureCategory category() {
        return category;
    }

    public void setCategory(FailureCategory category) {
        this.category = category;
    }

    public void setCategory(LessonCategory category) {
        this.category = category != null ? category.toFailureCategory() : null;
    }

    public String getRootCauseSummary() {
        return rootCauseSummary;
    }

    public void setRootCauseSummary(String rootCauseSummary) {
        this.rootCauseSummary = rootCauseSummary;
    }

    public String getFailedHypothesis() {
        return failedHypothesis;
    }

    public void setFailedHypothesis(String failedHypothesis) {
        this.failedHypothesis = failedHypothesis;
    }

    public List<String> getNegativeConstraints() {
        return negativeConstraints;
    }

    public String getNegativeConstraint() {
        if (negativeConstraints != null && !negativeConstraints.isEmpty()) {
            return String.join("; ", negativeConstraints);
        }
        return "";
    }

    public String negativeConstraint() {
        return getNegativeConstraint();
    }

    public String getContext() {
        if (rootCauseSummary != null && !rootCauseSummary.isEmpty()) {
            return rootCauseSummary;
        }
        if (failedHypothesis != null && !failedHypothesis.isEmpty()) {
            return failedHypothesis;
        }
        return "";
    }

    public void setNegativeConstraints(List<String> negativeConstraints) {
        this.negativeConstraints = (negativeConstraints != null) ? new ArrayList<>(negativeConstraints) : new ArrayList<>();
    }

    public void addNegativeConstraint(String constraint) {
        if (constraint != null && !constraint.isEmpty()) {
            this.negativeConstraints.add(constraint);
        }
    }

    public String getSuggestedPivot() {
        return suggestedPivot;
    }

    public String suggestedPivot() {
        return suggestedPivot;
    }

    public void setSuggestedPivot(String suggestedPivot) {
        this.suggestedPivot = suggestedPivot;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public double getConfidence() {
        return confidence;
    }

    public double confidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DistilledLesson that = (DistilledLesson) o;
        return timestamp == that.timestamp &&
                Double.compare(that.confidence, confidence) == 0 &&
                Objects.equals(lessonId, that.lessonId) &&
                Objects.equals(signature, that.signature) &&
                category == that.category &&
                Objects.equals(rootCauseSummary, that.rootCauseSummary) &&
                Objects.equals(failedHypothesis, that.failedHypothesis) &&
                Objects.equals(negativeConstraints, that.negativeConstraints) &&
                Objects.equals(suggestedPivot, that.suggestedPivot);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lessonId, signature, category, rootCauseSummary, failedHypothesis, negativeConstraints, suggestedPivot, timestamp, confidence);
    }

    @Override
    public String toString() {
        return "DistilledLesson{" +
                "lessonId='" + lessonId + '\'' +
                ", category=" + category +
                ", rootCauseSummary='" + rootCauseSummary + '\'' +
                ", timestamp=" + timestamp +
                ", confidence=" + confidence +
                '}';
    }

    public static class Builder {
        private String lessonId;
        private String signature;
        private FailureCategory category;
        private String rootCauseSummary;
        private String failedHypothesis;
        private List<String> negativeConstraints = new ArrayList<>();
        private String suggestedPivot;
        private long timestamp = System.currentTimeMillis();
        private double confidence = 1.0;

        public Builder lessonId(String lessonId) {
            this.lessonId = lessonId;
            return this;
        }

        public Builder signature(String signature) {
            this.signature = signature;
            return this;
        }

        public Builder category(FailureCategory category) {
            this.category = category;
            return this;
        }

        public Builder category(LessonCategory category) {
            this.category = category != null ? category.toFailureCategory() : null;
            return this;
        }

        public Builder rootCauseSummary(String rootCauseSummary) {
            this.rootCauseSummary = rootCauseSummary;
            return this;
        }

        public Builder failedHypothesis(String failedHypothesis) {
            this.failedHypothesis = failedHypothesis;
            return this;
        }

        public Builder negativeConstraint(String constraint) {
            if (constraint != null) {
                this.negativeConstraints.add(constraint);
            }
            return this;
        }

        public Builder negativeConstraints(List<String> constraints) {
            if (constraints != null) {
                this.negativeConstraints = new ArrayList<>(constraints);
            }
            return this;
        }

        public Builder suggestedPivot(String suggestedPivot) {
            this.suggestedPivot = suggestedPivot;
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder confidence(double confidence) {
            this.confidence = confidence;
            return this;
        }

        public DistilledLesson build() {
            return new DistilledLesson(lessonId, signature, category, rootCauseSummary, failedHypothesis, negativeConstraints, suggestedPivot, timestamp, confidence);
        }
    }
}
