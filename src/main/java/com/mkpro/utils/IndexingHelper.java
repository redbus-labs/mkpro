package com.mkpro.utils;

import com.mkpro.core.MkProContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public class IndexingHelper {
    public static void indexProject(MkProContext context) {
        System.out.println("Indexing project...");
        // Minimal implementation
        Path root = Path.of(".");
        try (Stream<Path> paths = Files.walk(root)) {
            long count = paths.filter(Files::isRegularFile).count();
            System.out.println("Indexed " + count + " files.");
        } catch (IOException e) {
            System.err.println("Error indexing: " + e.getMessage());
        }
    }
}
