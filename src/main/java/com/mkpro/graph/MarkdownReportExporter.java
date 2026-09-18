package com.mkpro.graph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implementation of GraphExporter that generates a Markdown summary report.
 */
public class MarkdownReportExporter implements GraphExporter {

    @Override
    public void export(ExtractionResult extraction, AnalysisResult analysis, Path outputPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Graphify Project Analysis Report\n\n");
        
        sb.append("## Project Summary\n");
        sb.append("- **Total Entities:** ").append(extraction.entities().size()).append("\n");
        sb.append("- **Total Relationships:** ").append(extraction.relationships().size()).append("\n\n");

        // 1. Centrality Scores
        sb.append("## Top 10 Most Central Nodes (PageRank)\n");
        sb.append("| Rank | Entity | Type | Score |\n");
        sb.append("|------|--------|------|-------|\n");
        
        List<Map.Entry<Entity, Double>> topNodes = analysis.centralityScores().entrySet().stream()
                .sorted(Map.Entry.<Entity, Double>comparingByValue().reversed())
                .limit(10)
                .collect(Collectors.toList());

        int rank = 1;
        for (Map.Entry<Entity, Double> entry : topNodes) {
            Entity entity = entry.getKey();
            sb.append("| ")
              .append(rank++).append(" | ")
              .append(entity.name()).append(" | ")
              .append(entity.type()).append(" | ")
              .append(String.format("%.4f", entry.getValue())).append(" |\n");
        }
        sb.append("\n");

        // 2. Structural Health Section
        sb.append("## Structural Health & Quality Metrics\n\n");

        // Dead Code
        sb.append("### Unreachable / Potential Dead Code\n");
        if (analysis.deadCode().isEmpty()) {
            sb.append("✓ No potential dead code or unreachable components detected!\n\n");
        } else {
            sb.append("The following ").append(analysis.deadCode().size())
              .append(" entities are unreachable from the project entry points:\n\n");
            sb.append("| Entity | Type |\n");
            sb.append("|--------|------|\n");
            for (Entity dead : analysis.deadCode()) {
                sb.append("| ").append(dead.name()).append(" | ").append(dead.type()).append(" |\n");
            }
            sb.append("\n");
        }

        // Circular Dependencies
        sb.append("### Circular Dependencies (Cycles)\n");
        if (analysis.cycles().isEmpty()) {
            sb.append("✓ No circular dependencies detected! (Perfect structural hierarchy)\n\n");
        } else {
            sb.append("⚠ Detected ").append(analysis.cycles().size())
              .append(" circular dependency groups in the codebase:\n\n");
            int cycleIndex = 1;
            for (Set<Entity> cycle : analysis.cycles()) {
                String cycleStr = cycle.stream()
                        .map(Entity::name)
                        .collect(Collectors.joining(" ⇄ "));
                sb.append(cycleIndex++).append(". ").append(cycleStr).append("\n");
            }
            sb.append("\n");
        }

        // Blast Radius
        sb.append("### Impact Assessment (Blast Radius of Top Central Nodes)\n");
        sb.append("Change propagation analysis for the top 5 most central components. If one of these changes, the listed upstream dependents are affected:\n\n");
        
        List<Entity> top5Central = topNodes.stream()
                .map(Map.Entry::getKey)
                .limit(5)
                .collect(Collectors.toList());

        for (Entity centralNode : top5Central) {
            Set<Entity> blast = analysis.blastRadius().get(centralNode);
            if (blast != null) {
                // Exclude the node itself for listing dependents
                Set<Entity> dependents = blast.stream()
                        .filter(e -> !e.equals(centralNode))
                        .collect(Collectors.toSet());

                sb.append("- **").append(centralNode.name()).append("** (").append(centralNode.type()).append(")\n");
                sb.append("  - **Blast Radius Size:** ").append(blast.size()).append(" entities (including itself)\n");
                if (dependents.isEmpty()) {
                    sb.append("  - **Dependents:** None (leaves/independent node)\n");
                } else {
                    String dependentsStr = dependents.stream()
                            .map(Entity::name)
                            .collect(Collectors.joining(", "));
                    sb.append("  - **Dependents:** ").append(dependentsStr).append("\n");
                }
            }
        }
        sb.append("\n");

        // 3. Relationship Statistics
        sb.append("## Relationship Statistics\n");
        Map<String, Long> edgeCounts = extraction.relationships().stream()
                .collect(Collectors.groupingBy(r -> r.type().name(), Collectors.counting()));
        
        for (Map.Entry<String, Long> entry : edgeCounts.entrySet()) {
            sb.append("- **").append(entry.getKey()).append(":** ").append(entry.getValue()).append("\n");
        }

        try {
            Files.writeString(outputPath.resolve("GRAPH_REPORT.md"), sb.toString());
        } catch (IOException e) {
            throw new RuntimeException("Failed to export Markdown report to " + outputPath, e);
        }
    }
}
