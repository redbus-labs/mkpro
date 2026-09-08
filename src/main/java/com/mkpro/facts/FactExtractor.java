package com.mkpro.facts;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Extracts structured relationship triples from Knowledge Scheduler summaries.
 * Uses LLM to identify subject-predicate-object relationships in text.
 * Extracted facts are added to the FactEngine graph with confidence 0.8.
 */
public class FactExtractor {

    private static final double EXTRACTED_CONFIDENCE = 0.8;
    private static final int MAX_EXTRACTIONS_PER_TOPIC = 10;

    private final FactEngine factEngine;
    private final Function<String, String> llmCallback;

    public FactExtractor(FactEngine factEngine, Function<String, String> llmCallback) {
        this.factEngine = factEngine;
        this.llmCallback = llmCallback;
    }

    /**
     * Extract relationships from a knowledge summary and add them to the fact graph.
     *
     * @param topicName The topic name (used as domain in the graph)
     * @param summary   The LLM-analyzed summary text
     * @return Number of facts extracted and added
     */
    public int extractAndAdd(String topicName, String summary) {
        if (summary == null || summary.isBlank() || llmCallback == null) return 0;
        if (factEngine == null) return 0;

        // Truncate long summaries to avoid huge prompts
        String text = summary.length() > 2000 ? summary.substring(0, 2000) : summary;

        int total = 0;

        // 1. Extract relationships
        String relPrompt = buildExtractionPrompt(topicName, text);
        String relResponse = llmCallback.apply(relPrompt);
        if (relResponse != null && !relResponse.isBlank()) {
            List<ExtractedTriple> triples = parseRelationshipResponse(relResponse);
            for (ExtractedTriple t : triples) {
                if (total >= MAX_EXTRACTIONS_PER_TOPIC) break;
                factEngine.addRelationship(t.subject, t.predicate, t.object, topicName, EXTRACTED_CONFIDENCE);
                total++;
            }
        }

        // 2. Extract math/science formulas
        String mathPrompt = buildMathExtractionPrompt(topicName, text);
        String mathResponse = llmCallback.apply(mathPrompt);
        if (mathResponse != null && !mathResponse.isBlank()) {
            List<ExtractedMathFact> mathFacts = parseMathResponse(mathResponse);
            for (ExtractedMathFact mf : mathFacts) {
                if (total >= MAX_EXTRACTIONS_PER_TOPIC) break;
                MathFact fact = new MathFact();
                fact.setKey("extracted." + topicName + "." + mf.name);
                fact.setFormula(mf.formula);
                fact.setKeywords(mf.keywords);
                fact.setScript(mf.script); // May be null for complex formulas
                factEngine.getStore().addMathFact(fact);
                total++;
            }
        }

        return total;
    }

    private String buildExtractionPrompt(String topic, String text) {
        return "Extract factual relationships from this knowledge summary about '" + topic + "'.\n\n" +
            "Text:\n---\n" + text + "\n---\n\n" +
            "Extract up to 10 subject-predicate-object triples. Use these predicates:\n" +
            "requires, replaces, extends, part_of, configures, alternative_to, uses, provides, type, manages\n\n" +
            "Format EACH triple on its own line as:\n" +
            "FACT: subject | predicate | object\n\n" +
            "Example:\n" +
            "FACT: Kubernetes HPA | requires | metrics-server\n" +
            "FACT: TLS 1.3 | replaces | TLS 1.2\n\n" +
            "Only output FACT: lines. No other text. If no clear relationships, output: NONE";
    }

    private String buildMathExtractionPrompt(String topic, String text) {
        return "Extract mathematical or scientific formulas from this text about '" + topic + "'.\n\n" +
            "Text:\n---\n" + text + "\n---\n\n" +
            "For each formula found, output in this EXACT format:\n" +
            "FORMULA: name | formula_expression | keyword1,keyword2,keyword3\n" +
            "SCRIPT: def verify(Map v) { ... Groovy one-liner returning [result: computed_value] ... }\n\n" +
            "Rules for SCRIPT:\n" +
            "- Only generate SCRIPT for simple arithmetic (add, subtract, multiply, divide, sqrt, pow, Math.PI)\n" +
            "- Variables come from v map: v.varname as double\n" +
            "- Return format: [result: value, unit: \"unit_name\"]\n" +
            "- If formula is too complex for a one-liner, write SCRIPT: NONE\n\n" +
            "Example:\n" +
            "FORMULA: kinetic_energy | KE = ½mv² | kinetic energy,velocity,mass\n" +
            "SCRIPT: def verify(Map v) { [result: 0.5*(v.m as double)*(v.v as double)*(v.v as double), unit: \"J\"] }\n\n" +
            "FORMULA: drag_coefficient | Cd = 2F/(ρv²A) | drag,aerodynamics,coefficient\n" +
            "SCRIPT: NONE\n\n" +
            "Output up to 5 formulas. If no formulas found, output: NONE";
    }

    private List<ExtractedTriple> parseRelationshipResponse(String response) {
        List<ExtractedTriple> triples = new ArrayList<>();

        if (response.trim().equals("NONE")) return triples;

        for (String line : response.split("\n")) {
            line = line.trim();
            if (!line.startsWith("FACT:")) continue;

            String content = line.substring(5).trim();
            String[] parts = content.split("\\|");
            if (parts.length != 3) continue;

            String subject = parts[0].trim();
            String predicate = parts[1].trim().toLowerCase();
            String object = parts[2].trim();

            if (!isValidPredicate(predicate)) continue;
            if (subject.length() < 2 || object.length() < 2) continue;

            triples.add(new ExtractedTriple(subject, predicate, object));
        }

        return triples;
    }

    private List<ExtractedMathFact> parseMathResponse(String response) {
        List<ExtractedMathFact> facts = new ArrayList<>();

        if (response.trim().equals("NONE")) return facts;

        String[] lines = response.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("FORMULA:")) continue;

            String content = line.substring(8).trim();
            String[] parts = content.split("\\|");
            if (parts.length < 3) continue;

            String name = parts[0].trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
            String formula = parts[1].trim();
            List<String> keywords = new ArrayList<>();
            for (String kw : parts[2].trim().split(",")) {
                kw = kw.trim().toLowerCase();
                if (!kw.isEmpty()) keywords.add(kw);
            }

            // Look for SCRIPT on next line
            String script = null;
            if (i + 1 < lines.length) {
                String nextLine = lines[i + 1].trim();
                if (nextLine.startsWith("SCRIPT:")) {
                    String scriptContent = nextLine.substring(7).trim();
                    if (!"NONE".equals(scriptContent) && scriptContent.contains("def verify")) {
                        // Validate: must contain basic safe patterns only
                        if (isScriptSafe(scriptContent)) {
                            script = scriptContent;
                        }
                    }
                    i++; // Skip script line
                }
            }

            if (name.length() >= 2 && formula.length() >= 3 && keywords.size() >= 2) {
                facts.add(new ExtractedMathFact(name, formula, keywords, script));
            }
        }

        return facts;
    }

    /**
     * Basic safety check for auto-generated Groovy scripts.
     * Only allow Math operations, basic arithmetic, and map access.
     */
    private boolean isScriptSafe(String script) {
        if (script == null) return false;
        // Block dangerous patterns
        String[] blocked = {"Runtime", "Process", "System", "exec", "File", "URL", "Socket",
                           "Thread", "Class.forName", "import", "new File", "new URL"};
        for (String b : blocked) {
            if (script.contains(b)) return false;
        }
        // Must be a single def verify function
        if (!script.contains("def verify(Map")) return false;
        // Reasonable length (one-liner)
        if (script.length() > 500) return false;
        return true;
    }

    private boolean isValidPredicate(String predicate) {
        return switch (predicate) {
            case "requires", "replaces", "extends", "part_of", "configures",
                 "alternative_to", "uses", "provides", "type", "manages",
                 "implements", "includes", "used_for", "prevents" -> true;
            default -> false;
        };
    }

    private record ExtractedTriple(String subject, String predicate, String object) {}
    private record ExtractedMathFact(String name, String formula, List<String> keywords, String script) {}
}
