package com.mkpro.facts;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.*;

/**
 * Scans project source files for facts without using LLM.
 * Extracts: constants, dependencies, config constraints, architectural comments.
 * Results added to FactEngine as "project facts" with confidence 0.9.
 */
public class ProjectFactScanner {

    private static final double PROJECT_FACT_CONFIDENCE = 0.9;
    private static final String DOMAIN = "project";

    // Patterns for constant extraction
    private static final Pattern JAVA_CONSTANT = Pattern.compile(
        "(?:static\\s+final|final\\s+static)\\s+\\w+\\s+(\\w+)\\s*=\\s*([\\d.]+)");
    private static final Pattern PYTHON_CONSTANT = Pattern.compile(
        "^([A-Z][A-Z_0-9]+)\\s*=\\s*([\\d.]+)", Pattern.MULTILINE);
    private static final Pattern JS_CONST = Pattern.compile(
        "(?:const|var|let)\\s+([A-Z][A-Z_0-9]+)\\s*=\\s*([\\d.]+)");

    // Patterns for dependency extraction
    private static final Pattern MAVEN_DEP = Pattern.compile(
        "<artifactId>(.*?)</artifactId>\\s*\\n\\s*<version>(.*?)</version>");
    private static final Pattern MAVEN_PARENT_VERSION = Pattern.compile(
        "<parent>.*?<artifactId>(.*?)</artifactId>.*?<version>(.*?)</version>", Pattern.DOTALL);
    private static final Pattern NPM_DEP = Pattern.compile(
        "\"([^\"]+)\"\\s*:\\s*\"([~^]?[\\d.]+)\"");
    private static final Pattern GRADLE_DEP = Pattern.compile(
        "(?:implementation|api|compile)\\s+['\"]([^'\"]+):([^'\"]+):([^'\"]+)['\"]");

    // Patterns for config constraints
    private static final Pattern YAML_NUMERIC = Pattern.compile(
        "^\\s*([a-z][a-z0-9_.\\-]+)\\s*:\\s*([\\d.]+)\\s*$", Pattern.MULTILINE);
    private static final Pattern PROPERTIES_NUMERIC = Pattern.compile(
        "^([a-z][a-z0-9_.\\-]+)\\s*=\\s*([\\d.]+)\\s*$", Pattern.MULTILINE);

    // Patterns for architectural comments
    private static final Pattern REQUIRES_COMMENT = Pattern.compile(
        "(?://|#|\\*)\\s*(?:requires?|depends on|needs?)\\s+(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORMULA_COMMENT = Pattern.compile(
        "(?://|#|\\*)\\s*(?:formula|equation|calculate[sd]? as)\\s*:?\\s*(.+)", Pattern.CASE_INSENSITIVE);

    private final FactEngine factEngine;
    private int factsAdded = 0;
    private GitIgnoreFilter gitIgnoreFilter;

    public ProjectFactScanner(FactEngine factEngine) {
        this.factEngine = factEngine;
    }

    /**
     * Scan the project directory and extract facts.
     * @return number of facts discovered and added
     */
    public int scan(Path projectRoot) {
        factsAdded = 0;
        gitIgnoreFilter = new GitIgnoreFilter(projectRoot);

        try {
            // 1. Scan dependency files (root level)
            scanFile(projectRoot.resolve("pom.xml"), this::scanMavenDeps);
            scanFile(projectRoot.resolve("package.json"), this::scanNpmDeps);
            scanFile(projectRoot.resolve("build.gradle"), this::scanGradleDeps);

            // 2. Scan config files (recursively, respecting .gitignore)
            scanTree(projectRoot, Set.of(".yaml", ".yml"), this::scanYamlConfig);
            scanTree(projectRoot, Set.of(".properties"), this::scanPropertiesConfig);

            // 3. Scan source files for constants and comments (recursively)
            scanTree(projectRoot, Set.of(".java"), this::scanJavaSource);
            scanTree(projectRoot, Set.of(".py"), this::scanPythonSource);
            scanTree(projectRoot, Set.of(".js", ".ts"), this::scanJsSource);

        } catch (Exception e) {
            // Non-fatal
        }

        return factsAdded;
    }

    // ═══ Dependency scanning ═══

    private void scanMavenDeps(Path file, String content) {
        // Extract parent/Spring Boot version
        Matcher m = MAVEN_PARENT_VERSION.matcher(content);
        if (m.find()) {
            String artifact = m.group(1);
            String version = m.group(2);
            addRelationship("project", "uses", artifact + " " + version);
            // Infer Java version requirement from Spring Boot version
            if (artifact.contains("spring-boot") && version.startsWith("3")) {
                addRelationship("project", "requires", "Java 17+");
            }
        }

        // Extract key dependencies
        Matcher depMatcher = MAVEN_DEP.matcher(content);
        while (depMatcher.find()) {
            String artifact = depMatcher.group(1);
            String version = depMatcher.group(2);
            if (!artifact.contains("${") && !version.contains("${")) {
                addRelationship("project", "uses", artifact + " " + version);
            }
        }
    }

    private void scanNpmDeps(Path file, String content) {
        // Only scan dependencies section (not devDependencies deep scan)
        int depIdx = content.indexOf("\"dependencies\"");
        if (depIdx < 0) return;
        int braceStart = content.indexOf("{", depIdx);
        int braceEnd = content.indexOf("}", braceStart);
        if (braceStart < 0 || braceEnd < 0) return;

        String depsSection = content.substring(braceStart, braceEnd);
        Matcher m = NPM_DEP.matcher(depsSection);
        while (m.find()) {
            addRelationship("project", "uses", m.group(1) + " " + m.group(2));
        }
    }

    private void scanGradleDeps(Path file, String content) {
        Matcher m = GRADLE_DEP.matcher(content);
        while (m.find()) {
            addRelationship("project", "uses", m.group(2) + " " + m.group(3));
        }
    }

    // ═══ Config scanning ═══

    private void scanYamlConfig(Path file, String content) {
        Matcher m = YAML_NUMERIC.matcher(content);
        while (m.find()) {
            String key = m.group(1);
            String value = m.group(2);
            // Only capture meaningful constraints
            if (isConstraintKey(key)) {
                addConstraint(key, value, file.getFileName().toString());
            }
        }
    }

    private void scanPropertiesConfig(Path file, String content) {
        Matcher m = PROPERTIES_NUMERIC.matcher(content);
        while (m.find()) {
            String key = m.group(1);
            String value = m.group(2);
            if (isConstraintKey(key)) {
                addConstraint(key, value, file.getFileName().toString());
            }
        }
    }

    // ═══ Source scanning ═══

    private void scanJavaSource(Path file, String content) {
        // Extract constants
        Matcher m = JAVA_CONSTANT.matcher(content);
        while (m.find()) {
            String name = m.group(1);
            String value = m.group(2);
            if (name.matches("[A-Z][A-Z_0-9]+") && name.length() >= 4) {
                addConstraint(name, value, file.getFileName().toString());
            }
        }

        // Extract architectural comments
        scanComments(file, content);
    }

    private void scanPythonSource(Path file, String content) {
        Matcher m = PYTHON_CONSTANT.matcher(content);
        while (m.find()) {
            String name = m.group(1);
            String value = m.group(2);
            if (name.length() >= 4) {
                addConstraint(name, value, file.getFileName().toString());
            }
        }
        scanComments(file, content);
    }

    private void scanJsSource(Path file, String content) {
        Matcher m = JS_CONST.matcher(content);
        while (m.find()) {
            String name = m.group(1);
            String value = m.group(2);
            if (name.length() >= 4) {
                addConstraint(name, value, file.getFileName().toString());
            }
        }
        scanComments(file, content);
    }

    private void scanComments(Path file, String content) {
        // "requires X" comments
        Matcher m = REQUIRES_COMMENT.matcher(content);
        while (m.find()) {
            String dep = m.group(1).trim();
            if (dep.length() > 2 && dep.length() < 60) {
                addRelationship("project", "requires", dep);
            }
        }

        // Formula comments
        m = FORMULA_COMMENT.matcher(content);
        while (m.find()) {
            String formula = m.group(1).trim();
            if (formula.length() > 3 && formula.length() < 100) {
                // Store as a text-only math fact
                MathFact fact = new MathFact();
                fact.setKey("project." + file.getFileName().toString().replaceAll("[^a-zA-Z0-9]", "_") + "." + factsAdded);
                fact.setFormula(formula);
                fact.setKeywords(List.of()); // No keywords — project-specific
                fact.setScript(null);
                factEngine.getStore().addMathFact(fact);
                factsAdded++;
            }
        }
    }

    // ═══ Helpers ═══

    private boolean isConstraintKey(String key) {
        String lower = key.toLowerCase();
        return lower.contains("max") || lower.contains("min") || lower.contains("timeout")
            || lower.contains("limit") || lower.contains("size") || lower.contains("port")
            || lower.contains("pool") || lower.contains("retry") || lower.contains("threshold")
            || lower.contains("interval") || lower.contains("capacity") || lower.contains("buffer");
    }

    private void addRelationship(String subject, String predicate, String object) {
        factEngine.addRelationship(subject, predicate, object, DOMAIN, PROJECT_FACT_CONFIDENCE);
        factsAdded++;
    }

    private void addConstraint(String key, String value, String source) {
        factEngine.addRelationship("project:" + source, "constraint", key + " = " + value, DOMAIN, PROJECT_FACT_CONFIDENCE);
        factsAdded++;
    }

    private void scanFile(Path file, java.util.function.BiConsumer<Path, String> scanner) {
        if (!Files.exists(file)) return;
        try {
            String content = Files.readString(file);
            scanner.accept(file, content);
        } catch (IOException e) { /* skip */ }
    }

    /**
     * Recursively walk the project tree, respecting .gitignore, and scan matching files.
     */
    private void scanTree(Path root, Set<String> extensions, java.util.function.BiConsumer<Path, String> scanner) {
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 15, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.size() > 100_000 || attrs.size() < 10) return FileVisitResult.CONTINUE;
                    if (gitIgnoreFilter != null && gitIgnoreFilter.isIgnored(file)) return FileVisitResult.CONTINUE;

                    String name = file.getFileName().toString();
                    boolean matches = false;
                    for (String ext : extensions) {
                        if (name.endsWith(ext)) { matches = true; break; }
                    }
                    if (!matches) return FileVisitResult.CONTINUE;

                    try {
                        String content = Files.readString(file);
                        scanner.accept(file, content);
                    } catch (IOException e) { /* skip */ }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (gitIgnoreFilter != null && !gitIgnoreFilter.shouldEnterDirectory(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) { /* skip */ }
    }
}
