package com.mkpro.facts;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads .gitignore and provides path filtering.
 * Supports basic gitignore patterns: directory/, *.ext, path/glob, negation (!).
 */
public class GitIgnoreFilter {

    private final List<IgnoreRule> rules = new ArrayList<>();
    private final Path root;

    // Always ignored regardless of .gitignore
    private static final List<String> ALWAYS_IGNORED = List.of(
        ".git", "node_modules", "target", "build", "dist", ".gradle",
        "__pycache__", ".mkpro", ".idea", ".vscode", ".settings",
        "out", ".cache", "coverage", ".nyc_output", "vendor"
    );

    public GitIgnoreFilter(Path projectRoot) {
        this.root = projectRoot;
        // Load .gitignore from project root
        Path gitignore = projectRoot.resolve(".gitignore");
        if (Files.exists(gitignore)) {
            try {
                List<String> lines = Files.readAllLines(gitignore);
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    rules.add(new IgnoreRule(line));
                }
            } catch (IOException e) { /* proceed without gitignore */ }
        }
    }

    /**
     * Check if a path should be ignored.
     * @param path Absolute path to check
     * @return true if the path should be skipped
     */
    public boolean isIgnored(Path path) {
        // Never ignore the root itself
        if (path.equals(root)) return false;

        // Check always-ignored directories
        String fileName = path.getFileName().toString();
        if (Files.isDirectory(path) && ALWAYS_IGNORED.contains(fileName)) {
            return true;
        }

        // Check .gitignore rules
        String relativePath = root.relativize(path).toString().replace('\\', '/');
        if (relativePath.isEmpty()) return false;

        boolean ignored = false;

        for (IgnoreRule rule : rules) {
            if (rule.negation) {
                if (matchesPattern(relativePath, fileName, rule.pattern)) {
                    ignored = false; // Negation un-ignores
                }
            } else {
                if (matchesPattern(relativePath, fileName, rule.pattern)) {
                    ignored = true;
                }
            }
        }

        return ignored;
    }

    /**
     * Check if a directory should be entered during tree walk.
     */
    public boolean shouldEnterDirectory(Path dir) {
        if (dir.equals(root)) return true; // Always enter root
        String name = dir.getFileName().toString();
        if (ALWAYS_IGNORED.contains(name)) return false;
        return !isIgnored(dir);
    }

    private boolean matchesPattern(String relativePath, String fileName, String pattern) {
        // Directory pattern: "dirname/" matches any directory with that name
        if (pattern.endsWith("/")) {
            String dirName = pattern.substring(0, pattern.length() - 1);
            return fileName.equals(dirName) || relativePath.contains(dirName + "/");
        }

        // Extension pattern: "*.ext"
        if (pattern.startsWith("*.")) {
            String ext = pattern.substring(1); // ".ext"
            return fileName.endsWith(ext);
        }

        // Path pattern: "path/to/thing"
        if (pattern.contains("/")) {
            return relativePath.startsWith(pattern) || relativePath.equals(pattern);
        }

        // Simple name match
        return fileName.equals(pattern) || relativePath.contains("/" + pattern);
    }

    private static class IgnoreRule {
        final String pattern;
        final boolean negation;

        IgnoreRule(String line) {
            if (line.startsWith("!")) {
                this.negation = true;
                this.pattern = line.substring(1).trim();
            } else {
                this.negation = false;
                this.pattern = line;
            }
        }
    }
}
