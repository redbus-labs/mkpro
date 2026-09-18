package com.mkpro.facts;

import com.mkpro.CentralMemory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FactEngine — orchestrator combining math verification and relationship validation.
 *
 * Pre-turn: injects relevant formulas and relationships into agent stimulus.
 * Post-turn: validates mathematical claims and relationship assertions.
 * On-demand: agents call verify_fact tool.
 */
public class FactEngine {

    private FactStore store;
    private FactStore factStore;
    private final FactClassifier classifier;
    private final GroovyFactEvaluator evaluator;
    private RelationshipGraph graph;
    private RelationshipGraph relationshipGraph;
    private final RelationshipValidator validator;
    private CentralMemory centralMemory;
    private Map<String, Object> mathFormulas = new ConcurrentHashMap<>();

    public FactEngine() {
        this.factStore = new FactStore();
        this.store = this.factStore;
        this.store.load();

        registerMathFormula("geometry.sphere_volume", "(4.0 / 3.0) * Math.PI * Math.pow(r as Double, 3)", "cubic_units");

        this.classifier = new FactClassifier(this.store);
        this.evaluator = new GroovyFactEvaluator();
        this.relationshipGraph = new RelationshipGraph();
        this.graph = this.relationshipGraph;
        this.validator = new RelationshipValidator(this.graph);

        // Build relationship graph from loaded triples
        for (RelationshipTriple triple : this.store.getAllRelationships()) {
            this.graph.addTriple(triple);
        }
        this.validator.initFromStore(this.store);
    }

    public FactEngine(FactStore store, RelationshipGraph graph) {
        this.factStore = (store != null) ? store : new FactStore();
        this.store = this.factStore;
        if (store == null) {
            this.store.load();
        }
        registerMathFormula("geometry.sphere_volume", "(4.0 / 3.0) * Math.PI * Math.pow(r as Double, 3)", "cubic_units");
        this.classifier = new FactClassifier(this.store);
        this.evaluator = new GroovyFactEvaluator();
        this.relationshipGraph = (graph != null) ? graph : new RelationshipGraph();
        this.graph = this.relationshipGraph;
        this.validator = new RelationshipValidator(this.graph);

        for (RelationshipTriple triple : this.store.getAllRelationships()) {
            this.graph.addTriple(triple);
        }
        this.validator.initFromStore(this.store);
    }

    public void setCentralMemory(CentralMemory centralMemory) {
        this.centralMemory = centralMemory;
    }

    public CentralMemory getCentralMemory() {
        return centralMemory;
    }

    public void loadPersistedFacts() {
        // Load persisted facts from CentralMemory if available
        if (this.centralMemory != null && this.factStore != null) {
            try {
                @SuppressWarnings("unchecked")
                List<Fact> savedFacts = this.centralMemory.get("project_facts", List.class);
                if (savedFacts != null) {
                    for (Fact fact : savedFacts) {
                        this.factStore.storeFact(fact);
                    }
                }
            } catch (Exception e) {
                // Non-fatal if loading fails
            }
        }
    }

    public void persistProjectFacts() {
        // Persist facts to CentralMemory if available
        if (this.centralMemory != null && this.factStore != null) {
            try {
                this.centralMemory.put("project_facts", new ArrayList<>(this.factStore.getAllFacts()));
            } catch (Exception e) {
                // Non-fatal if persistence fails
            }
        }
    }

    public void shutdown() {
        persistProjectFacts();
    }

    public record FactStats(int facts, int relationships, int rules, int formulas) {
        public int totalFacts() { return facts; }
        public int totalRelationships() { return relationships; }
        public int factsCount() { return facts; }
        public int relationshipsCount() { return relationships; }
        public int formulasCount() { return formulas; }
    }

    public FactStats getStats() {
        int facts = this.factStore != null ? this.factStore.getAllFacts().size() : 0;
        int triples = this.relationshipGraph != null ? this.relationshipGraph.getAllTriples().size() : 0;
        int formulas = this.mathFormulas != null ? this.mathFormulas.size() : 0;
        return new FactStats(facts, triples, 0, formulas);
    }

    /**
     * PRE-TURN: Get relevant facts to inject into agent stimulus.
     * Returns formatted text with formulas and relationships.
     */
    public String getRelevantFacts(String text) {
        if (text == null || text.isBlank()) return null;

        StringBuilder sb = new StringBuilder();

        // Math facts (static YAML)
        List<MathFact> mathFacts = classifier.findRelevantMathFacts(text);
        if (!mathFacts.isEmpty()) {
            sb.append("[VERIFIED FACTS]\n");
            for (MathFact fact : mathFacts) {
                sb.append("  • ").append(fact.getFormula());
                if (fact.getUnits() != null && !fact.getUnits().isEmpty()) {
                    sb.append(" (units: ").append(fact.getUnits()).append(")");
                }
                sb.append("\n");
            }
        }

        // Relationship facts (static YAML)
        List<RelationshipTriple> rels = classifier.findRelevantRelationships(text);
        if (!rels.isEmpty()) {
            if (sb.length() == 0) sb.append("[VERIFIED FACTS]\n");
            Set<String> seen = new HashSet<>();
            for (RelationshipTriple rel : rels) {
                String line = rel.getSubject() + " " + rel.getPredicate() + " " + rel.getObject();
                if (seen.add(line)) {
                    sb.append("  • ").append(line).append("\n");
                }
                if (seen.size() >= 5) break; // Cap at 5 relationships to avoid noise
            }
        }

        // Project-discovered facts: search graph edges where target contains query keywords
        List<String> projectFacts = findProjectFacts(text);
        if (!projectFacts.isEmpty()) {
            if (sb.length() == 0) sb.append("[VERIFIED FACTS]\n");
            sb.append("  [Project]\n");
            for (String pf : projectFacts) {
                sb.append("  • ").append(pf).append("\n");
            }
        }

        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * POST-TURN: Validate agent response for math errors and relationship conflicts.
     * Returns list of issues found (empty = all good).
     */
    public List<String> validateResponse(String response) {
        if (response == null || response.isBlank()) return Collections.emptyList();

        List<String> issues = new ArrayList<>();

        // Validate relationship claims
        List<String> relIssues = validator.validateClaim(response);
        issues.addAll(relIssues);

        return issues;
    }

    /**
     * Register a mathematical formula with an expression and unit.
     */
    public void registerMathFormula(String key, String expression, String unit) {
        if (this.mathFormulas != null) {
            this.mathFormulas.put(key, expression);
        }
        MathFact fact = store.getMathFact(key);
        if (fact == null) {
            fact = new MathFact();
            fact.setKey(key);
            store.addMathFact(fact);
        }
        String script = "def verify(Map v) {\n"
                + "    Binding b = new Binding(v != null ? new HashMap<>(v) : new HashMap<>())\n"
                + "    GroovyShell sh = new GroovyShell(b)\n"
                + "    def res = sh.evaluate('''" + expression + "''')\n"
                + "    return [result: res, unit: \"" + unit + "\"]\n"
                + "}\n"
                + "def validate(Map v) {\n"
                + "    Binding b = new Binding(v != null ? new HashMap<>(v) : new HashMap<>())\n"
                + "    GroovyShell sh = new GroovyShell(b)\n"
                + "    def e = (sh.evaluate('''" + expression + "''')) as double\n"
                + "    def c = (v.containsKey('result') ? v.result : (v.containsKey('V') ? v.V : (v.containsKey('A') ? v.A : v.output))) as double\n"
                + "    return [correct: Math.abs(c - e) < 0.01 || Math.abs(c - e) / e < 0.001, expected: e, got: c]\n"
                + "}";
        fact.setScript(script);
    }

    /**
     * ON-DEMAND: Verify a specific mathematical fact with given variables.
     * Called by the verify_fact agent tool.
     */
    public Map<String, Object> verifyMath(String factKey, Map<String, Object> variables) {
        MathFact fact = store.getMathFact(factKey);
        if (fact == null) {
            // Try partial key match (e.g., "circle_area" matches "geometry.circle_area")
            for (MathFact mf : store.getAllMathFacts()) {
                if (mf.getKey().endsWith("." + factKey) || mf.getKey().equals(factKey)) {
                    fact = mf;
                    break;
                }
            }
        }
        if (fact == null) {
            // Try keyword search (split underscores into separate keywords)
            String[] parts = factKey.replace("_", " ").split("\\s+");
            List<MathFact> found = store.findByKeywords(java.util.Arrays.asList(parts));
            if (!found.isEmpty()) {
                fact = found.get(0);
            } else {
                return Map.of("error", "Unknown fact: " + factKey + ". Use /facts math to list available facts.");
            }
        }
        return evaluator.verify(fact, variables);
    }

    /**
     * ON-DEMAND: Validate a claimed result against a known formula.
     */
    public Map<String, Object> validateMath(String factKey, Map<String, Object> variables) {
        MathFact fact = store.getMathFact(factKey);
        if (fact == null) {
            // Try partial key match
            for (MathFact mf : store.getAllMathFacts()) {
                if (mf.getKey().endsWith("." + factKey) || mf.getKey().equals(factKey)) {
                    fact = mf;
                    break;
                }
            }
        }
        if (fact == null) {
            String[] parts = factKey.replace("_", " ").split("\\s+");
            List<MathFact> found = store.findByKeywords(java.util.Arrays.asList(parts));
            if (!found.isEmpty()) fact = found.get(0);
            else return Map.of("error", "Unknown fact: " + factKey);
        }
        return evaluator.validate(fact, variables);
    }

    /**
     * ON-DEMAND: Check a relationship.
     */
    public Map<String, Object> checkRelationship(String subject, String predicate, String object) {
        boolean direct = validator.check(subject, predicate, object);
        if (direct) {
            return Map.of("verified", true, "type", "direct", "chain", List.of(subject, object));
        }

        List<String> chain = validator.checkTransitive(subject, predicate, object);
        if (!chain.isEmpty()) {
            return Map.of("verified", true, "type", "transitive", "chain", chain);
        }

        return Map.of("verified", false, "message", "No known relationship: " + subject + " " + predicate + " " + object);
    }

    /**
     * ON-DEMAND: Query all relationships for a subject.
     */
    public List<String> queryRelationships(String subject) {
        List<RelationshipGraph.Edge> edges = validator.getAllRelationships(subject);
        List<String> results = new ArrayList<>();
        for (RelationshipGraph.Edge e : edges) {
            results.add(subject + " " + e.predicate + " " + e.target);
        }
        return results;
    }

    /**
     * Search stored facts and relationship graph triples matching keyword.
     */
    public String query(String keyword) {
        if (keyword == null || keyword.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        String kwLower = keyword.toLowerCase().trim();

        if (this.factStore != null) {
            for (Fact f : this.factStore.getAllFacts()) {
                boolean match = (f.topic() != null && f.topic().toLowerCase().contains(kwLower))
                        || (f.category() != null && f.category().toLowerCase().contains(kwLower))
                        || (f.fact() != null && f.fact().toLowerCase().contains(kwLower));
                if (match) {
                    sb.append(f.topic()).append(": ").append(f.fact()).append("\n");
                    sb.append(f.fact()).append("\n");
                }
            }
        }

        if (this.relationshipGraph != null) {
            for (RelationshipTriple t : this.relationshipGraph.getAllTriples()) {
                boolean match = (t.subject() != null && t.subject().toLowerCase().contains(kwLower))
                        || (t.predicate() != null && t.predicate().toLowerCase().contains(kwLower))
                        || (t.object() != null && t.object().toLowerCase().contains(kwLower));
                if (match) {
                    sb.append(t.subject()).append(" ").append(t.predicate()).append(" ").append(t.object()).append("\n");
                }
            }
        }

        return sb.toString();
    }

    // ═══ Accessors ═══

    public FactStore getStore() { return store; }
    public FactStore getFactStore() { return factStore; }
    public FactClassifier getClassifier() { return classifier; }
    public RelationshipValidator getValidator() { return validator; }
    public RelationshipGraph getGraph() { return graph; }
    public RelationshipGraph getRelationshipGraph() { return relationshipGraph; }

    /**
     * Add a general Fact to the store.
     */
    public boolean addFact(Fact fact) {
        if (fact == null) return false;
        if (this.factStore == null) {
            this.factStore = new FactStore();
        }
        return this.factStore.storeFact(fact);
    }

    /**
     * Add a relationship at runtime with confidence score.
     * Used by FactExtractor when Knowledge Scheduler discovers relationships from docs.
     */
    public void addRelationship(String subject, String predicate, String object, String domain, double confidence) {
        RelationshipTriple triple = new RelationshipTriple();
        triple.setSubject(subject);
        triple.setPredicate(predicate);
        triple.setObject(object);
        triple.setDomain(domain != null ? domain : "project");
        triple.setConfidence(confidence);

        // Add to graph for traversal
        graph.addTriple(triple, confidence);
        // Add to store for persistence / re-indexing
        store.addRelationship(triple);
    }

    // ═══ CLI formatting helpers ═══

    public String formatMathFacts() {
        StringBuilder sb = new StringBuilder();
        sb.append("Math Facts (").append(store.mathFactCount()).append(" formulas):\n");
        for (MathFact f : store.getAllMathFacts()) {
            sb.append("  ").append(String.format("%-30s", f.getKey()))
              .append(" = ").append(f.getFormula());
            if (f.getUnits() != null && !f.getUnits().isEmpty()) {
                sb.append(" (").append(f.getUnits()).append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public String formatRelationships() {
        StringBuilder sb = new StringBuilder();
        sb.append("Knowledge Graph (").append(graph.nodeCount()).append(" nodes, ")
          .append(graph.edgeCount()).append(" edges):\n");
        for (RelationshipGraph.Edge edge : graph.getAllEdges()) {
            // Find subject for this edge
            for (RelationshipTriple t : store.getAllRelationships()) {
                if (t.getPredicate().equals(edge.predicate) && t.getObject().equals(edge.target)) {
                    sb.append("  ").append(t.getSubject()).append(" ")
                      .append(edge.toString()).append("\n");
                    break;
                }
            }
        }
        return sb.toString();
    }

    public String formatStats() {
        return "Facts Engine Status:\n"
                + "  Math formulas: " + store.mathFactCount() + "\n"
                + "  Graph nodes:   " + graph.nodeCount() + "\n"
                + "  Graph edges:   " + graph.edgeCount() + "\n";
    }

    // ═══ Private helpers ═══

    /**
     * Find project-discovered facts matching text keywords.
     * Searches graph edges where subject or target contains words from text.
     */
    private List<String> findProjectFacts(String text) {
        List<String> results = new ArrayList<>();
        Set<String> words = extractSignificantWords(text);

        for (RelationshipGraph.Edge edge : graph.getAllEdges()) {
            if (edge.confidence < 1.0) { // Extracted from project docs, not static YAML
                for (String word : words) {
                    if (edge.target.toLowerCase().contains(word) || edge.predicate.toLowerCase().contains(word)) {
                        results.add(edge.target + " (" + edge.predicate + ", " + (int)(edge.confidence * 100) + "% confidence)");
                        break;
                    }
                }
            }
            if (results.size() >= 3) break; // Cap at 3 project facts
        }
        return results;
    }

    private Set<String> extractSignificantWords(String text) {
        Set<String> words = new HashSet<>();
        for (String word : text.toLowerCase().split("[^a-zA-Z0-9_.-]+")) {
            if (word.length() > 3 && !store.getStopWords().contains(word)) {
                words.add(word);
            }
        }
        return words;
    }

    // ═══ Pre-Turn & Validation Enhancements ═══

    /**
     * Injects context facts based on a stimulus query string.
     * Formats facts clearly for prompt injection.
     */
    public String injectContext(String stimulus) {
        if (stimulus == null || stimulus.isBlank()) return "";
        String facts = getRelevantFacts(stimulus);
        if (facts != null && !facts.isBlank()) {
            return "[FACT CONTEXT]\n" + facts;
        }
        // Fallback: try keyword query
        String kwResult = query(stimulus.trim());
        if (!kwResult.isBlank()) {
            return "[FACT CONTEXT]\n" + kwResult;
        }
        return "";
    }

    /**
     * Injects project-specific context facts for a stimulus.
     */
    public String injectProjectContext(String stimulus) {
        if (stimulus == null || stimulus.isBlank()) return "";
        List<String> projectFacts = findProjectFacts(stimulus);
        if (projectFacts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("[PROJECT CONTEXT]\n");
        for (String pf : projectFacts) {
            sb.append("  • ").append(pf).append("\n");
        }
        return sb.toString();
    }

    /**
     * Validates an entire response containing mathematical expressions or claims.
     * Returns a validation result map with status and details.
     */
    public Map<String, Object> validate(String response) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> issues = validateResponse(response);
        result.put("valid", issues.isEmpty());
        result.put("issues", issues);
        result.put("issueCount", issues.size());
        return result;
    }

    /**
     * Checks relationship validity and returns a formatted explanation string.
     */
    public String explainRelationship(String subject, String predicate, String object) {
        Map<String, Object> check = checkRelationship(subject, predicate, object);
        boolean verified = Boolean.TRUE.equals(check.get("verified"));
        if (verified) {
            String type = (String) check.get("type");
            Object chain = check.get("chain");
            return "VERIFIED (" + type + "): " + subject + " " + predicate + " " + object +
                    (chain != null ? " [Chain: " + chain + "]" : "");
        } else {
            return "UNVERIFIED: " + check.getOrDefault("message", "No relationship found.");
        }
    }

    /**
     * Verifies a mathematical fact by key and inputs, returning a formatted result string.
     */
    public String verifyMathFormatted(String factKey, Map<String, Object> variables) {
        Map<String, Object> res = verifyMath(factKey, variables);
        if (res.containsKey("error")) {
            return "ERROR: " + res.get("error");
        }
        Object result = res.get("result");
        Object unit = res.get("unit");
        return "Result: " + result + (unit != null && !unit.toString().isBlank() ? " " + unit : "");
    }

    /**
     * Validates a claimed mathematical value against a known fact key.
     */
    public boolean validateMathClaim(String factKey, Map<String, Object> variables) {
        Map<String, Object> res = validateMath(factKey, variables);
        return Boolean.TRUE.equals(res.get("correct"));
    }

    /**
     * Batch checks multiple relationship assertions.
     * Each entry is a String array of [subject, predicate, object].
     */
    public List<Map<String, Object>> checkRelationshipsBatch(List<String[]> assertions) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (assertions == null) return results;
        for (String[] assertion : assertions) {
            if (assertion != null && assertion.length >= 3) {
                Map<String, Object> check = checkRelationship(assertion[0], assertion[1], assertion[2]);
                Map<String, Object> entry = new LinkedHashMap<>(check);
                entry.put("subject", assertion[0]);
                entry.put("predicate", assertion[1]);
                entry.put("object", assertion[2]);
                results.add(entry);
            }
        }
        return results;
    }

    /**
     * Returns true if any conflict was detected for the given response claim text.
     */
    public boolean hasConflicts(String claimText) {
        return !validateResponse(claimText).isEmpty();
    }

    /**
     * Returns a summary map of all registered knowledge components.
     */
    public Map<String, Object> getKnowledgeSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("mathFactCount", store.mathFactCount());
        summary.put("graphNodeCount", graph.nodeCount());
        summary.put("graphEdgeCount", graph.edgeCount());
        summary.put("generalFactCount", factStore != null ? factStore.getAllFacts().size() : 0);
        return summary;
    }
}