package com.mkpro.facts;

/**
 * A subject-predicate-object relationship triple.
 */
public class RelationshipTriple {
    private String domain;     // e.g. "kubernetes", "java", "networking"
    private String subject;    // e.g. "HPA"
    private String predicate;  // e.g. "requires"
    private String object;     // e.g. "metrics-server"

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getPredicate() { return predicate; }
    public void setPredicate(String predicate) { this.predicate = predicate; }

    public String getObject() { return object; }
    public void setObject(String object) { this.object = object; }

    @Override
    public String toString() {
        return subject + " --" + predicate + "--> " + object;
    }
}
