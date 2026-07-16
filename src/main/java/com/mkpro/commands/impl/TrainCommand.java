package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.routing.MarkovRouter;
import com.mkpro.routing.MarkovTrainer;
import com.mkpro.utils.PathUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.mkpro.MkPro.*;

/**
 * On-demand training command for the Markov Router.
 * 
 * Usage:
 *   /train              - Re-train from all JSONL files in datajsonl/
 *   /train status       - Show model stats (observations, categories, confidence)
 *   /train reset        - Clear the model and retrain from scratch
 *   /train threshold N  - Set confidence threshold (e.g., /train threshold 70)
 */
public class TrainCommand implements Command {

    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        MarkovRouter router = context.getMarkovRouter();
        if (router == null) {
            System.out.println(ANSI_RED + "Markov Router not initialized." + ANSI_RESET);
            return;
        }

        String subCommand = args.length > 0 ? args[0].toLowerCase() : "run";

        switch (subCommand) {
            case "status":
                showStatus(router);
                break;
            case "reset":
                resetAndRetrain(router, context);
                break;
            case "threshold":
                setThreshold(args, router);
                break;
            default:
                trainNow(router, context);
                break;
        }
    }

    private void trainNow(MarkovRouter router, MkProContext context) {
        Path dataDir = PathUtils.getProjectPath().resolve("datajsonl");
        if (!Files.isDirectory(dataDir)) {
            System.out.println(ANSI_YELLOW + "No datajsonl/ directory found. Use /export first to generate training data." + ANSI_RESET);
            return;
        }

        System.out.println(ANSI_BLUE + "Training Markov Router from datajsonl/..." + ANSI_RESET);
        int before = router.getTotalObservations();
        int trained = MarkovTrainer.trainFromDirectory(router, dataDir);
        int after = router.getTotalObservations();

        System.out.println(ANSI_GREEN + "Training complete:" + ANSI_RESET);
        System.out.println("  Examples processed: " + trained);
        System.out.println("  Total observations: " + before + " → " + after + " (+" + (after - before) + ")");
        System.out.println("  Confidence threshold: " + (int)(router.getConfidenceThreshold() * 100) + "%");

        // Save model
        try {
            Path modelPath = PathUtils.getProjectPath().resolve(".mkpro").resolve("markov_model.dat");
            router.save(modelPath);
            System.out.println(ANSI_GREEN + "  Model saved to .mkpro/markov_model.dat" + ANSI_RESET);
        } catch (Exception e) {
            System.out.println(ANSI_YELLOW + "  Warning: could not save model: " + e.getMessage() + ANSI_RESET);
        }
    }

    private void resetAndRetrain(MarkovRouter router, MkProContext context) {
        System.out.println(ANSI_YELLOW + "Resetting Markov model..." + ANSI_RESET);
        
        // Create fresh router
        MarkovRouter fresh = new MarkovRouter(router.getConfidenceThreshold());
        context.setMarkovRouter(fresh);

        // Retrain
        Path dataDir = PathUtils.getProjectPath().resolve("datajsonl");
        if (Files.isDirectory(dataDir)) {
            int trained = MarkovTrainer.trainFromDirectory(fresh, dataDir);
            System.out.println(ANSI_GREEN + "Reset + retrained: " + trained + " examples, " + fresh.getTotalObservations() + " observations." + ANSI_RESET);
            
            try {
                Path modelPath = PathUtils.getProjectPath().resolve(".mkpro").resolve("markov_model.dat");
                fresh.save(modelPath);
            } catch (Exception e) { /* Silent */ }
        } else {
            System.out.println(ANSI_YELLOW + "No datajsonl/ found. Model is empty — will learn from live usage." + ANSI_RESET);
        }
    }

    private void showStatus(MarkovRouter router) {
        System.out.println(ANSI_CYAN + "\n── Markov Router Status ──" + ANSI_RESET);
        System.out.println("  Total observations: " + router.getTotalObservations());
        System.out.println("  Confidence threshold: " + (int)(router.getConfidenceThreshold() * 100) + "%");
        System.out.println("  Active: " + (router.getTotalObservations() > 20 ? ANSI_GREEN + "YES" : ANSI_YELLOW + "NO (need 20+ observations)") + ANSI_RESET);

        var matrix = router.getCategoryToAgentMatrix();
        if (!matrix.isEmpty()) {
            System.out.println("\n  Category → Top Agent:");
            for (var entry : matrix.entrySet()) {
                String category = entry.getKey();
                // Skip GENERAL — it's not a real routing category
                if ("GENERAL".equals(category)) continue;
                
                var agents = entry.getValue();
                int total = agents.values().stream().mapToInt(Integer::intValue).sum();
                
                // Find top agent
                String topAgent = "";
                int topCount = 0;
                for (var a : agents.entrySet()) {
                    if (a.getValue() > topCount) {
                        topCount = a.getValue();
                        topAgent = a.getKey();
                    }
                }
                int pct = total > 0 ? (topCount * 100 / total) : 0;
                System.out.println("    " + String.format("%-15s → %-15s (%d%%, %d samples)", 
                    category, topAgent, pct, total));
            }
        }
        
        // Show learned patterns
        var learnedPatterns = router.getLearnedPatterns();
        if (learnedPatterns != null && !learnedPatterns.isEmpty()) {
            System.out.println("\n  Learned Patterns (from training data):");
            for (var entry : learnedPatterns.entrySet()) {
                String category = entry.getKey();
                var patterns = entry.getValue();
                // Show first 5 patterns per category
                java.util.List<String> display = new java.util.ArrayList<>(patterns);
                String preview = String.join(", ", display.subList(0, Math.min(5, display.size())));
                if (display.size() > 5) preview += " (+" + (display.size() - 5) + " more)";
                System.out.println("    " + String.format("%-15s: %s", category, preview));
            }
        }

        // Show transition probabilities
        var transitions = router.getTransitionMatrix();
        if (!transitions.isEmpty()) {
            System.out.println("\n  Transition Probabilities (P(next | category, last)):");
            for (var catEntry : transitions.entrySet()) {
                String category = catEntry.getKey();
                if ("GENERAL".equals(category)) continue;
                var lastAgentMap = catEntry.getValue();
                
                System.out.println("    " + ANSI_CYAN + category + ANSI_RESET + ":");
                for (var lastEntry : lastAgentMap.entrySet()) {
                    String lastAgent = lastEntry.getKey();
                    var nextAgents = lastEntry.getValue();
                    int total = nextAgents.values().stream().mapToInt(Integer::intValue).sum();
                    
                    // Show top 3 transitions from this agent
                    java.util.List<java.util.Map.Entry<String, Integer>> sorted = new java.util.ArrayList<>(nextAgents.entrySet());
                    sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                    
                    StringBuilder sb = new StringBuilder();
                    sb.append(String.format("      %-15s → ", lastAgent));
                    int shown = 0;
                    for (var next : sorted) {
                        if (shown >= 3) break;
                        int pct = total > 0 ? (next.getValue() * 100 / total) : 0;
                        if (pct < 5) break; // Skip tiny probabilities
                        if (shown > 0) sb.append(", ");
                        sb.append(next.getKey()).append("(").append(pct).append("%)");
                        shown++;
                    }
                    if (shown > 0) {
                        System.out.println(sb.toString());
                    }
                }
            }
        }
        
        System.out.println();
    }

    private void setThreshold(String[] args, MarkovRouter router) {
        if (args.length < 2) {
            System.out.println("Current threshold: " + (int)(router.getConfidenceThreshold() * 100) + "%");
            System.out.println("Usage: /train threshold <percentage>  (e.g., /train threshold 70)");
            return;
        }

        try {
            int pct = Integer.parseInt(args[1]);
            if (pct < 10 || pct > 99) {
                System.out.println(ANSI_RED + "Threshold must be between 10 and 99." + ANSI_RESET);
                return;
            }
            double threshold = pct / 100.0;
            router.setConfidenceThreshold(threshold);
            System.out.println(ANSI_GREEN + "Markov confidence threshold set to " + pct + "%." + ANSI_RESET);

            // Save model with new threshold
            try {
                Path modelPath = PathUtils.getProjectPath().resolve(".mkpro").resolve("markov_model.dat");
                router.save(modelPath);
            } catch (Exception e) { /* Silent */ }
        } catch (NumberFormatException e) {
            System.out.println(ANSI_RED + "Invalid number. Usage: /train threshold 70" + ANSI_RESET);
        }
    }

    @Override
    public String getName() {
        return "train";
    }

    @Override
    public String getDescription() {
        return "Train Markov Router. Usage: /train [status|reset|threshold <N>]";
    }
}
