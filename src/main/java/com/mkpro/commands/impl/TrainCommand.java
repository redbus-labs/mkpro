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
 *   /train           - Re-train from all JSONL files in datajsonl/
 *   /train status    - Show model stats (observations, categories, confidence)
 *   /train reset     - Clear the model and retrain from scratch
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
        System.out.println();
    }

    @Override
    public String getName() {
        return "train";
    }

    @Override
    public String getDescription() {
        return "Train Markov Router from JSONL data. Usage: /train [status|reset]";
    }
}
