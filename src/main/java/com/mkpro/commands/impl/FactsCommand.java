package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.facts.*;
import static com.mkpro.ui.AnsiColors.*;

import java.util.List;
import java.util.Map;

/**
 * /facts command — browse loaded facts, test verification, query relationships.
 *
 * Usage:
 *   /facts              - Show stats (math facts count, relationships count, graph size)
 *   /facts math         - List all math fact domains and formulas
 *   /facts rels         - List all relationship domains
 *   /facts verify <key> <vars>  - Run verify on a math fact (e.g., /facts verify circle_area r=5)
 *   /facts check <s> <p> <o>    - Check a relationship
 *   /facts query <subject>      - Get all relationships for a subject
 */
public class FactsCommand implements Command {

    @Override
    public String getName() {
        return "facts";
    }

    @Override
    public String getDescription() {
        return "Browse and test verified facts. Usage: /facts [math|rels|verify|check|query]";
    }

    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        FactEngine engine = context.getFactEngine();
        if (engine == null) {
            System.out.println(ANSI_YELLOW + "[Facts] FactEngine not initialized." + ANSI_RESET);
            return;
        }

        if (args.length == 0) {
            showStats(engine);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "math" -> showMathFacts(engine);
            case "rels", "relationships" -> showRelationships(engine);
            case "verify" -> verifyMath(args, engine);
            case "check" -> checkRelationship(args, engine);
            case "query" -> queryRelationships(args, engine);
            case "project" -> showProjectFacts(engine);
            default -> showStats(engine);
        }
    }

    private void showStats(FactEngine engine) {
        System.out.println(ANSI_CYAN + "\n── Verified Facts Status ──" + ANSI_RESET);
        System.out.println("  " + engine.getStats());

        // Count project-specific facts
        int projectRels = 0;
        int projectMath = 0;
        for (var edge : engine.getGraph().getAllEdges()) {
            if (edge.domain != null && edge.domain.startsWith("project")) projectRels++;
        }
        for (var fact : engine.getStore().getAllMathFacts()) {
            if (fact.getKey().startsWith("project.")) projectMath++;
        }
        if (projectRels > 0 || projectMath > 0) {
            System.out.println("  Project-specific: " + projectRels + " relationships, " + projectMath + " formulas");
        }

        System.out.println();
        System.out.println("  Commands:");
        System.out.println("    /facts math              List all math formulas");
        System.out.println("    /facts rels              List all relationship domains");
        System.out.println("    /facts project           Show project-discovered facts");
        System.out.println("    /facts verify <key> <vars>  Compute a formula");
        System.out.println("    /facts check <s> <p> <o>    Check a relationship");
        System.out.println("    /facts query <subject>      Query relationships");
        System.out.println();
    }

    private void showMathFacts(FactEngine engine) {
        System.out.println(ANSI_CYAN + "\n── Math Facts ──" + ANSI_RESET);
        String lastDomain = "";
        for (MathFact fact : engine.getStore().getAllMathFacts()) {
            String domain = fact.getKey().contains(".") ? fact.getKey().substring(0, fact.getKey().indexOf('.')) : "";
            if (!domain.equals(lastDomain)) {
                System.out.println(ANSI_GREEN + "\n  " + domain + ":" + ANSI_RESET);
                lastDomain = domain;
            }
            System.out.println("    " + fact.getKey() + ": " + ANSI_DIM + fact.getFormula() + ANSI_RESET);
        }
        System.out.println();
    }

    private void showRelationships(FactEngine engine) {
        System.out.println(ANSI_CYAN + "\n── Relationships ──" + ANSI_RESET);
        String lastDomain = "";
        for (RelationshipTriple t : engine.getStore().getAllRelationships()) {
            if (!t.getDomain().equals(lastDomain)) {
                System.out.println(ANSI_GREEN + "\n  " + t.getDomain() + ":" + ANSI_RESET);
                lastDomain = t.getDomain();
            }
            System.out.println("    " + t.getSubject() + " " + ANSI_DIM + "--" + t.getPredicate() + "-->" + ANSI_RESET + " " + t.getObject());
        }
        System.out.println();
    }

    private void verifyMath(String[] args, FactEngine engine) {
        if (args.length < 3) {
            System.out.println(ANSI_YELLOW + "Usage: /facts verify <fact_key> <var=value> [var=value...]" + ANSI_RESET);
            System.out.println("  Example: /facts verify circle_area r=5");
            System.out.println("  Example: /facts verify pythagorean a=3 b=4");
            return;
        }

        String key = args[1];
        java.util.Map<String, Object> vars = new java.util.HashMap<>();
        for (int i = 2; i < args.length; i++) {
            String[] parts = args[i].split("=", 2);
            if (parts.length == 2) {
                try {
                    vars.put(parts[0], Double.parseDouble(parts[1]));
                } catch (NumberFormatException e) {
                    vars.put(parts[0], parts[1]);
                }
            }
        }

        Map<String, Object> result = engine.verifyMath(key, vars);
        if (result.containsKey("error")) {
            System.out.println(ANSI_RED + "  Error: " + result.get("error") + ANSI_RESET);
        } else {
            System.out.println(ANSI_GREEN + "  ✓ Result: " + result.get("result") +
                (result.containsKey("unit") ? " " + result.get("unit") : "") + ANSI_RESET);
        }
    }

    private void checkRelationship(String[] args, FactEngine engine) {
        if (args.length < 4) {
            System.out.println(ANSI_YELLOW + "Usage: /facts check <subject> <predicate> <object>" + ANSI_RESET);
            System.out.println("  Example: /facts check HPA requires metrics-server");
            return;
        }

        String subject = args[1];
        String predicate = args[2];
        String object = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));

        Map<String, Object> result = engine.checkRelationship(subject, predicate, object);
        boolean verified = Boolean.TRUE.equals(result.get("verified"));

        if (verified) {
            String type = (String) result.getOrDefault("type", "");
            List<?> chain = (List<?>) result.get("chain");
            System.out.println(ANSI_GREEN + "  ✓ Verified (" + type + "): " +
                (chain != null ? String.join(" → ", chain.stream().map(Object::toString).toList()) : subject + " → " + object) +
                ANSI_RESET);
        } else {
            System.out.println(ANSI_YELLOW + "  ✗ Not verified: " + result.getOrDefault("message", "unknown") + ANSI_RESET);
        }
    }

    private void queryRelationships(String[] args, FactEngine engine) {
        if (args.length < 2) {
            System.out.println(ANSI_YELLOW + "Usage: /facts query <subject>" + ANSI_RESET);
            System.out.println("  Example: /facts query \"Spring Boot 3\"");
            return;
        }

        String subject = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        List<String> rels = engine.queryRelationships(subject);

        if (rels.isEmpty()) {
            System.out.println(ANSI_YELLOW + "  No relationships found for: " + subject + ANSI_RESET);
        } else {
            System.out.println(ANSI_GREEN + "  " + subject + ":" + ANSI_RESET);
            for (String rel : rels) {
                System.out.println("    " + rel);
            }
        }
    }

    private void showProjectFacts(FactEngine engine) {
        System.out.println(ANSI_CYAN + "\n── Project-Discovered Facts ──" + ANSI_RESET);

        // Show project relationships
        boolean hasAny = false;
        System.out.println(ANSI_GREEN + "\n  Relationships:" + ANSI_RESET);
        for (var edge : engine.getGraph().getAllEdges()) {
            if (edge.domain != null && edge.domain.startsWith("project")) {
                String confidence = edge.confidence < 1.0 ? " (" + (int)(edge.confidence * 100) + "%)" : "";
                System.out.println("    " + edge.domain + ": " + ANSI_DIM + "--" + edge.predicate + "--> " + edge.target + confidence + ANSI_RESET);
                hasAny = true;
            }
        }

        // Show project math facts
        System.out.println(ANSI_GREEN + "\n  Formulas/Constants:" + ANSI_RESET);
        for (MathFact fact : engine.getStore().getAllMathFacts()) {
            if (fact.getKey().startsWith("project.")) {
                System.out.println("    " + fact.getKey() + ": " + ANSI_DIM + fact.getFormula() + ANSI_RESET);
                hasAny = true;
            }
        }

        if (!hasAny) {
            System.out.println(ANSI_YELLOW + "\n  No project facts discovered yet. Run /index or /index deep to scan." + ANSI_RESET);
        }
        System.out.println();
    }
}
