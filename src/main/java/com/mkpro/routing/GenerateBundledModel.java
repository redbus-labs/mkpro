package com.mkpro.routing;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility to generate the bundled markov_model.dat for resources.
 * Run: java -cp target/mkpro-4.0.0.jar com.mkpro.routing.GenerateBundledModel
 */
public class GenerateBundledModel {
    public static void main(String[] args) throws Exception {
        Path dataDir = Paths.get("datajsonl");
        Path outputPath = Paths.get("src/main/resources/markov_model_default.dat");

        System.out.println("Training from: " + dataDir.toAbsolutePath());

        MarkovRouter router = new MarkovRouter();
        int trained = MarkovTrainer.trainFromDirectory(router, dataDir);

        System.out.println("Trained on " + trained + " examples, " + router.getTotalObservations() + " observations.");

        router.save(outputPath);
        System.out.println("Saved to: " + outputPath.toAbsolutePath());
    }
}
