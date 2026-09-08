package com.mkpro.facts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and indexes facts from facts.yaml.
 * Provides lookup by domain key and keyword search.
 */
public class FactStore {

    private final Map<String, MathFact> mathFacts = new ConcurrentHashMap<>();
    private final List<RelationshipTriple> relationships = new ArrayList<>();
    private final Map<String, List<String>> keywordIndex = new ConcurrentHashMap<>(); // keyword → list of fact keys
    private Set<String> stopWords = Set.of(); // loaded from YAML

    public void load() {
        try (InputStream is = getClass().getResourceAsStream("/facts.yaml")) {
            if (is == null) {
                System.err.println("[FactStore] facts.yaml not found in resources");
                return;
            }
            ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
            JsonNode root = yaml.readTree(is);
            loadStopWords(root.get("stop_words"));
            loadMathFacts(root.get("math"));
            loadMathFacts(root.get("physics"));
            loadMathFacts(root.get("cs"));
            loadRelationships(root.get("relationships"));
        } catch (Exception e) {
            System.err.println("[FactStore] Error loading facts.yaml: " + e.getMessage());
        }
    }

    private void loadStopWords(JsonNode node) {
        if (node == null || !node.isArray()) return;
        Set<String> words = new java.util.HashSet<>();
        for (JsonNode word : node) {
            words.add(word.asText().toLowerCase());
        }
        stopWords = Collections.unmodifiableSet(words);
    }

    public Set<String> getStopWords() {
        return stopWords;
    }

    private void loadMathFacts(JsonNode mathNode) {
        if (mathNode == null) return;
        Iterator<Map.Entry<String, JsonNode>> domains = mathNode.fields();
        while (domains.hasNext()) {
            Map.Entry<String, JsonNode> domain = domains.next();
            String domainName = domain.getKey();
            Iterator<Map.Entry<String, JsonNode>> facts = domain.getValue().fields();
            while (facts.hasNext()) {
                Map.Entry<String, JsonNode> factEntry = facts.next();
                String factName = factEntry.getKey();
                JsonNode node = factEntry.getValue();

                String key = domainName + "." + factName;
                MathFact fact = new MathFact();
                fact.setKey(key);
                fact.setFormula(node.has("formula") ? node.get("formula").asText() : "");
                fact.setScript(node.has("script") ? node.get("script").asText() : null);

                List<String> keywords = new ArrayList<>();
                if (node.has("keywords") && node.get("keywords").isArray()) {
                    for (JsonNode kw : node.get("keywords")) {
                        keywords.add(kw.asText().toLowerCase());
                    }
                }
                fact.setKeywords(keywords);

                if (node.has("units")) {
                    Map<String, String> units = new HashMap<>();
                    node.get("units").fields().forEachRemaining(e -> units.put(e.getKey(), e.getValue().asText()));
                    fact.setUnits(units);
                }

                mathFacts.put(key, fact);

                // Build keyword index
                for (String kw : keywords) {
                    keywordIndex.computeIfAbsent(kw, k -> new ArrayList<>()).add(key);
                }
            }
        }
    }

    private void loadRelationships(JsonNode relNode) {
        if (relNode == null) return;
        Iterator<Map.Entry<String, JsonNode>> domains = relNode.fields();
        while (domains.hasNext()) {
            Map.Entry<String, JsonNode> domain = domains.next();
            String domainName = domain.getKey();
            JsonNode triples = domain.getValue();
            if (triples.isArray()) {
                for (JsonNode t : triples) {
                    RelationshipTriple triple = new RelationshipTriple();
                    triple.setDomain(domainName);
                    triple.setSubject(t.has("subject") ? t.get("subject").asText() : "");
                    triple.setPredicate(t.has("predicate") ? t.get("predicate").asText() : "");
                    triple.setObject(t.has("object") ? t.get("object").asText() : "");
                    relationships.add(triple);
                }
            }
        }
    }

    public MathFact getMathFact(String key) {
        return mathFacts.get(key);
    }

    public Collection<MathFact> getAllMathFacts() {
        return mathFacts.values();
    }

    public List<RelationshipTriple> getAllRelationships() {
        return Collections.unmodifiableList(relationships);
    }

    /**
     * Find math facts matching any of the given keywords.
     */
    public List<MathFact> findByKeywords(List<String> keywords) {
        Set<String> matchedKeys = new LinkedHashSet<>();
        for (String kw : keywords) {
            List<String> keys = keywordIndex.get(kw.toLowerCase());
            if (keys != null) matchedKeys.addAll(keys);
        }
        List<MathFact> results = new ArrayList<>();
        for (String key : matchedKeys) {
            MathFact fact = mathFacts.get(key);
            if (fact != null) results.add(fact);
        }
        return results;
    }

    public int mathFactCount() { return mathFacts.size(); }
    public int relationshipCount() { return relationships.size(); }

    /**
     * Add a relationship triple at runtime (from Knowledge Scheduler extraction).
     */
    public void addRelationship(RelationshipTriple triple) {
        if (triple == null || triple.getSubject() == null || triple.getPredicate() == null) return;
        relationships.add(triple);
    }

    /**
     * Add a math fact at runtime.
     */
    public void addMathFact(MathFact fact) {
        if (fact == null || fact.getKey() == null) return;
        mathFacts.put(fact.getKey(), fact);
        // Update keyword index
        for (String kw : fact.getKeywords()) {
            keywordIndex.computeIfAbsent(kw, k -> new ArrayList<>()).add(fact.getKey());
        }
    }
}
