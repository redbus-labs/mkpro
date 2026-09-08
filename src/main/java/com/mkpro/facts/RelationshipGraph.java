package com.mkpro.facts;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory directed graph of relationship triples.
 * Supports BFS transitive traversal and contradiction detection.
 * Nodes are subjects/objects, edges are labeled predicates.
 */
public class RelationshipGraph {

    // adjacency: node → list of (predicate, target)
    private final Map<String, List<Edge>> outgoing = new ConcurrentHashMap<>();
    private final Map<String, List<Edge>> incoming = new ConcurrentHashMap<>();

    public static class Edge {
        public final String predicate;
        public final String target;
        public final String domain;
        public final double confidence; // 1.0 = static YAML, 0.7-0.9 = extracted from docs

        public Edge(String predicate, String target, String domain) {
            this(predicate, target, domain, 1.0);
        }

        public Edge(String predicate, String target, String domain, double confidence) {
            this.predicate = predicate;
            this.target = target;
            this.domain = domain;
            this.confidence = confidence;
        }

        @Override
        public String toString() {
            return "--" + predicate + "--> " + target + (confidence < 1.0 ? " (" + (int)(confidence*100) + "%)" : "");
        }
    }

    /**
     * Add a relationship triple to the graph (confidence = 1.0).
     */
    public void addTriple(RelationshipTriple triple) {
        addTriple(triple, 1.0);
    }

    /**
     * Add a relationship triple with explicit confidence score.
     */
    public void addTriple(RelationshipTriple triple, double confidence) {
        String subject = normalize(triple.getSubject());
        String object = normalize(triple.getObject());
        String predicate = triple.getPredicate();
        String domain = triple.getDomain();

        outgoing.computeIfAbsent(subject, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(new Edge(predicate, object, domain, confidence));
        incoming.computeIfAbsent(object, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(new Edge(predicate, subject, domain, confidence));
    }

    /**
     * Direct check: does subject have predicate → object?
     */
    public boolean check(String subject, String predicate, String object) {
        List<Edge> edges = outgoing.get(normalize(subject));
        if (edges == null) return false;
        String normObj = normalize(object);
        for (Edge e : edges) {
            if (e.predicate.equals(predicate) && normalize(e.target).equals(normObj)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Transitive check: can we reach object from subject via predicate chains?
     * Returns the chain if found, empty list if not.
     */
    public List<String> checkTransitive(String subject, String predicate, String object) {
        String start = normalize(subject);
        String end = normalize(object);

        // BFS
        Queue<List<String>> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();
        queue.add(List.of(start));
        visited.add(start);

        while (!queue.isEmpty()) {
            List<String> path = queue.poll();
            String current = path.get(path.size() - 1);

            List<Edge> edges = outgoing.get(current);
            if (edges == null) continue;

            for (Edge e : edges) {
                if (!e.predicate.equals(predicate)) continue;
                String next = normalize(e.target);
                if (next.equals(end)) {
                    List<String> result = new ArrayList<>(path);
                    result.add(next);
                    return result;
                }
                if (!visited.contains(next)) {
                    visited.add(next);
                    List<String> newPath = new ArrayList<>(path);
                    newPath.add(next);
                    queue.add(newPath);
                }
            }
        }
        return Collections.emptyList(); // Not reachable
    }

    /**
     * Query: get all objects reachable from subject via predicate.
     */
    public List<String> query(String subject, String predicate) {
        List<Edge> edges = outgoing.get(normalize(subject));
        if (edges == null) return Collections.emptyList();

        List<String> results = new ArrayList<>();
        for (Edge e : edges) {
            if (e.predicate.equals(predicate)) {
                results.add(e.target);
            }
        }
        return results;
    }

    /**
     * Get all relationships for a subject (all predicates).
     */
    public List<Edge> getRelationships(String subject) {
        List<Edge> edges = outgoing.get(normalize(subject));
        return edges != null ? Collections.unmodifiableList(edges) : Collections.emptyList();
    }

    /**
     * Check if adding a new triple would contradict existing knowledge.
     * Returns description of contradiction, or null if no conflict.
     */
    public String detectContradiction(String subject, String predicate, String object) {
        // Check direct contradiction: "A replaces B" + "B replaces A"
        if ("replaces".equals(predicate)) {
            if (check(object, "replaces", subject)) {
                return "Contradiction: '" + object + " replaces " + subject + "' already exists. " +
                       "Cannot have both directions.";
            }
        }

        // Check cycle in "requires": A requires B requires A
        if ("requires".equals(predicate)) {
            List<String> chain = checkTransitive(object, "requires", subject);
            if (!chain.isEmpty()) {
                return "Circular dependency: " + subject + " requires " + object +
                       ", but " + String.join(" → ", chain) + " → " + subject;
            }
        }

        return null; // No contradiction
    }

    /**
     * Number of nodes in the graph.
     */
    public int nodeCount() {
        Set<String> nodes = new HashSet<>();
        nodes.addAll(outgoing.keySet());
        nodes.addAll(incoming.keySet());
        return nodes.size();
    }

    /**
     * Number of edges in the graph.
     */
    public int edgeCount() {
        return outgoing.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Get all edges (for iteration/stats).
     */
    public List<Edge> getAllEdges() {
        List<Edge> all = new ArrayList<>();
        for (List<Edge> edges : outgoing.values()) {
            all.addAll(edges);
        }
        return all;
    }

    private String normalize(String s) {
        return s != null ? s.toLowerCase().trim() : "";
    }
}
