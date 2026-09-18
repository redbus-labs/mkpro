package com.mkpro.facts;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Function;

/**
 * Agent-assisted deep fact discovery. Uses LLM to analyze key project files
 * and extract complex relationships, architectural patterns, and formulas
 * that regex can't detect.
 *
 * Triggered by: /index --deep
 * Analyzes: README, main configs, core classes (top 10 by importance)
 */
public class AgentFactDiscovery {

    private static final double AGENT_FACT_CONFIDENCE = 0.85;
    private static final int MAX_FILES_TO_ANALYZE = 10;
    private static final int MAX_FILE_SIZE = 8000; // chars sent to LLM

    private final FactEngine factEngine;
    private final Function<String, String> llmCallback;
    private int factsAdded = 0;

    public AgentFactDiscovery(FactEngine factEngine, Function<String, String> llmCallback) {
        this.factEngine = factEngine;
        this.llmCallback = llmCallback;
    }

    /**
     * Run deep analysis on key project files.
     * @return number of facts discovered
     */
    public int analyze(Path projectRoot) {
        factsAdded = 0;

        if (llmCallback == null || factEngine == null) return 0;

        // 1. Build the file tree
        GitIgnoreFilter gitIgnore = new GitIgnoreFilter(projectRoot);
        List<String> fileTree = buildFileTree(projectRoot, gitIgnore);

        if (fileTree.isEmpty()) {
            System.out.println("\u001b[33m  [Deep Discovery] No files found in project.\u001b[0m");
            return 0;
        }

        System.out.println("\u001b[90m  [Deep Discovery] File tree: " + fileTree.size() + " files\u001b[0m");

        // 2. Ask LLM to pick the most important files
        List<String> selectedFiles = askLlmToPickFiles(fileTree);

        if (selectedFiles.isEmpty()) {
            System.out.println("\u001b[33m  [Deep Discovery] LLM selected no files for analysis.\u001b[0m");
            return 0;
        }

        System.out.println("\u001b[36m  [Deep Discovery] Analyzing " + selectedFiles.size() + " key file(s):\u001b[0m");
        for (String f : selectedFiles) {
            System.out.println("\u001b[90m    → " + f + "\u001b[0m");
        }

        // 3. Analyze each selected file
        for (String relativePath : selectedFiles) {
            Path file = projectRoot.resolve(relativePath);
            if (!Files.exists(file) || !Files.isRegularFile(file)) continue;

            try {
                String content = readTruncated(file, MAX_FILE_SIZE);
                if (content == null || content.isBlank()) continue;

                analyzeFile(relativePath, content);
            } catch (Exception e) {
                // Skip problematic files
            }
        }

        return factsAdded;
    }

    // Directories to always skip (build artifacts, dependencies, caches)
    private static final Set<String> SKIP_DIRS = Set.of(
        "node_modules", ".git", "vendor", "dist", "build", "__pycache__",
        ".gradle", ".idea", ".vscode", "target", "bin", "obj", ".next",
        ".nuxt", "coverage", ".terraform", ".cache", ".mvn", ".settings",
        "venv", ".venv", "env", ".tox", ".mypy_cache", ".pytest_cache"
    );

    /**
     * Build a file tree listing (respecting .gitignore).
     * Skips binary files, sorts root-level first for better LLM visibility.
     */
    private List<String> buildFileTree(Path root, GitIgnoreFilter gitIgnore) {
        List<String> tree = new ArrayList<>();
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 12, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (tree.size() >= 1000) return FileVisitResult.TERMINATE;
                    if (attrs.size() > 1_000_000 || attrs.size() < 5) return FileVisitResult.CONTINUE;
                    if (gitIgnore.isIgnored(file)) return FileVisitResult.CONTINUE;

                    String name = file.getFileName().toString().toLowerCase();
                    // Skip binary/media/lock files
                    if (isBinaryFile(name)) return FileVisitResult.CONTINUE;

                    tree.add(root.relativize(file).toString().replace('\\', '/'));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) return FileVisitResult.CONTINUE;
                    if (!gitIgnore.shouldEnterDirectory(dir)) return FileVisitResult.SKIP_SUBTREE;
                    String dirName = dir.getFileName().toString().toLowerCase();
                    if (SKIP_DIRS.contains(dirName)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) { /* skip */ }

        // Sort: root-level files first (configs/manifests), then by path depth
        tree.sort((a, b) -> {
            int depthA = (int) a.chars().filter(c -> c == '/').count();
            int depthB = (int) b.chars().filter(c -> c == '/').count();
            return Integer.compare(depthA, depthB);
        });

        return tree;
    }

    private boolean isBinaryFile(String name) {
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") ||
            name.endsWith(".gif") || name.endsWith(".ico") || name.endsWith(".svg") ||
            name.endsWith(".woff") || name.endsWith(".woff2") || name.endsWith(".ttf") ||
            name.endsWith(".eot") || name.endsWith(".mp4") || name.endsWith(".mp3") ||
            name.endsWith(".zip") || name.endsWith(".tar") || name.endsWith(".gz") ||
            name.endsWith(".jar") || name.endsWith(".class") || name.endsWith(".pyc") ||
            name.endsWith(".so") || name.endsWith(".dll") || name.endsWith(".exe") ||
            name.endsWith(".lock") || name.endsWith(".sum") || name.endsWith(".min.js") ||
            name.endsWith(".min.css") || name.endsWith(".map") || name.endsWith(".wasm") ||
            name.endsWith(".o") || name.endsWith(".a") || name.endsWith(".lib") ||
            name.endsWith(".db") || name.endsWith(".sqlite");
    }

    /**
     * Ask LLM to select the most important files from the tree.
     */
    private List<String> askLlmToPickFiles(List<String> fileTree) {
        String treeText = String.join("\n", fileTree);
        if (treeText.length() > 12000) {
            treeText = treeText.substring(0, 12000) + "\n... (" + fileTree.size() + " total files, truncated)";
        }

        String prompt = "Here is a project's file listing:\n\n" + treeText + "\n\n" +
            "Select the " + MAX_FILES_TO_ANALYZE + " most important files for understanding this project's " +
            "architecture, dependencies, technology relationships, configuration constraints, and any mathematical/scientific formulas.\n\n" +
            "Prioritize:\n" +
            "- README and documentation\n" +
            "- Dependency manifests (pom.xml, package.json, go.mod, Cargo.toml, etc.)\n" +
            "- Main entry points and core business logic (main.go, app.py, index.ts, etc.)\n" +
            "- Configuration files with limits/thresholds (application.yml, config.*, .env.example)\n" +
            "- Infrastructure definitions (Docker, K8s, Terraform, docker-compose)\n" +
            "- Core service/controller/handler files\n\n" +
            "Respond with ONLY the exact file paths from the listing above, one per line. No numbering, no explanation, no markdown.";

        String response = llmCallback.apply(prompt);
        if (response == null || response.isBlank()) return Collections.emptyList();

        List<String> selected = new ArrayList<>();
        Set<String> fileTreeSet = new HashSet<>(fileTree);

        for (String line : response.split("\n")) {
            String path = line.trim();
            // Strip common LLM formatting
            path = path.replaceAll("^[\\d.\\-*•]+[\\s.)]+", ""); // numbering/bullets
            path = path.replaceAll("^`|`$", ""); // backticks
            path = path.replaceAll("^- ", ""); // dash bullets
            path = path.trim();
            if (path.isEmpty() || path.startsWith("#") || path.startsWith("Note")) continue;

            // Exact match
            if (fileTreeSet.contains(path)) {
                selected.add(path);
            } else {
                // Fuzzy match: LLM might format slightly differently
                String matched = fuzzyMatch(path, fileTree);
                if (matched != null) {
                    selected.add(matched);
                }
            }

            if (selected.size() >= MAX_FILES_TO_ANALYZE) break;
        }

        return selected;
    }

    /**
     * Fuzzy match a path against the file tree.
     * Handles: trailing/leading slashes, case differences, partial paths.
     */
    private String fuzzyMatch(String candidate, List<String> fileTree) {
        // Normalize
        String normalized = candidate.replace('\\', '/');
        if (normalized.startsWith("./")) normalized = normalized.substring(2);
        if (normalized.startsWith("/")) normalized = normalized.substring(1);

        // Try exact after normalization
        for (String actual : fileTree) {
            if (actual.equals(normalized)) return actual;
        }

        // Try suffix match (LLM might omit a prefix directory)
        for (String actual : fileTree) {
            if (actual.endsWith("/" + normalized) || actual.endsWith(normalized)) {
                return actual;
            }
        }

        // Try case-insensitive
        String lowerCandidate = normalized.toLowerCase();
        for (String actual : fileTree) {
            if (actual.toLowerCase().equals(lowerCandidate)) return actual;
        }

        return null;
    }

    private void analyzeFile(String relativePath, String content) {
        String prompt = "Analyze this source file and extract structured facts.\n\n" +
            "File: " + relativePath + "\n" +
            "```\n" + content + "\n```\n\n" +
            "Extract:\n" +
            "1. Technology relationships (what requires/uses/replaces what)\n" +
            "2. Mathematical formulas or computation logic\n" +
            "3. Architectural patterns and constraints\n" +
            "4. Configuration limits and thresholds\n\n" +
            "Format EACH finding as one of:\n" +
            "FACT: subject | predicate | object\n" +
            "FORMULA: name | expression | keyword1,keyword2\n" +
            "CONSTRAINT: name | condition | source\n\n" +
            "Predicates: requires, uses, replaces, configures, provides, implements, extends\n\n" +
            "Example:\n" +
            "FACT: UserService | requires | Redis\n" +
            "FORMULA: retry_delay | base_delay × 2^attempt | retry,backoff,exponential\n" +
            "CONSTRAINT: max_connections | <= 100 | application.yaml\n\n" +
            "Output up to 10 findings. If nothing meaningful, output: NONE";

        String response = llmCallback.apply(prompt);
        if (response == null || response.isBlank() || "NONE".equals(response.trim())) return;

        parseAndAddFindings(response, relativePath);
    }

    private void parseAndAddFindings(String response, String sourceFile) {
        for (String line : response.split("\n")) {
            line = line.trim();

            if (line.startsWith("FACT:")) {
                String content = line.substring(5).trim();
                String[] parts = content.split("\\|");
                if (parts.length == 3) {
                    String subject = parts[0].trim();
                    String predicate = parts[1].trim().toLowerCase();
                    String object = parts[2].trim();
                    if (subject.length() >= 2 && object.length() >= 2 && isValidPredicate(predicate)) {
                        factEngine.addRelationship(subject, predicate, object, "project:" + sourceFile, AGENT_FACT_CONFIDENCE);
                        factsAdded++;
                    }
                }
            } else if (line.startsWith("FORMULA:")) {
                String content = line.substring(8).trim();
                String[] parts = content.split("\\|");
                if (parts.length >= 2) {
                    String name = parts[0].trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
                    String formula = parts[1].trim();
                    List<String> keywords = new ArrayList<>();
                    if (parts.length >= 3) {
                        for (String kw : parts[2].trim().split(",")) {
                            kw = kw.trim().toLowerCase();
                            if (!kw.isEmpty()) keywords.add(kw);
                        }
                    }
                    if (name.length() >= 2 && formula.length() >= 3) {
                        MathFact fact = new MathFact();
                        fact.setKey("project." + name);
                        fact.setFormula(formula);
                        fact.setKeywords(keywords);
                        fact.setScript(null); // Agent-discovered, no auto-generated script
                        factEngine.getStore().addMathFact(fact);
                        factsAdded++;
                    }
                }
            } else if (line.startsWith("CONSTRAINT:")) {
                String content = line.substring(11).trim();
                String[] parts = content.split("\\|");
                if (parts.length >= 2) {
                    String name = parts[0].trim();
                    String condition = parts[1].trim();
                    String source = parts.length >= 3 ? parts[2].trim() : sourceFile;
                    factEngine.addRelationship("project", "constraint",
                        name + " " + condition, "project:" + source, AGENT_FACT_CONFIDENCE);
                    factsAdded++;
                }
            }
        }
    }

    private boolean isValidPredicate(String predicate) {
        return switch (predicate) {
            case "requires", "replaces", "extends", "part_of", "configures",
                 "alternative_to", "uses", "provides", "type", "manages",
                 "implements", "includes", "used_for", "prevents" -> true;
            default -> false;
        };
    }

    private String readTruncated(Path file, int maxChars) throws IOException {
        String content = Files.readString(file);
        if (content.length() > maxChars) {
            return content.substring(0, maxChars) + "\n... [truncated]";
        }
        return content;
    }

}
