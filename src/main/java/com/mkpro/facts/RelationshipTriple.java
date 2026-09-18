package com.mkpro.facts;

/**
 * A subject-predicate-object relationship triple.
 */
public class RelationshipTriple {
    private String domain;     // e.g. "kubernetes", "java", "networking"
    private String subject;    // e.g. "HPA"
    private String predicate;  // e.g. "requires"
    private String object;     // e.g. "metrics-server"
    private double confidence = 1.0;

    public RelationshipTriple() {}

    public RelationshipTriple(Object subject, String predicate, String object, double confidence) {
        this.subject = subject != null ? subject.toString() : "UNKNOWN";
        this.predicate = predicate;
        this.object = object;
        this.confidence = confidence;
    }

    public RelationshipTriple(Object subject, String predicate, String object) {
        this(subject, predicate, object, 1.0);
    }

    public RelationshipTriple(String subject, String predicate, String object, String domain) {
        this(subject, predicate, object, 1.0);
        this.domain = domain;
    }

    public RelationshipTriple(String subject, String predicate, String object, String domain, double confidence) {
        this(subject, predicate, object, confidence);
        this.domain = domain;
    }

    public String getDomain() { return domain; }
    public String domain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public String getSubject() { return subject; }
    public String subject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getPredicate() { return predicate; }
    public String predicate() { return predicate; }
    public void setPredicate(String predicate) { this.predicate = predicate; }

    public String getObject() { return object; }
    public String object() { return object; }
    public void setObject(String object) { this.object = object; }

    public double getConfidence() { return confidence; }
    public double confidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    @Override
    public String toString() {
        return subject + " --" + predicate + "--> " + object;
    }
}
