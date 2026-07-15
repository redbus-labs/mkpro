package com.mkpro.routing;

import java.util.*;
import java.util.regex.Pattern;

/**
 * IntentClassifier categorizes user input into task categories using keyword matching.
 * Categories map to likely agent delegations in the Markov transition matrix.
 *
 * Categories: coding, testing, devops, git, docs, security, architecture, database,
 *             sysadmin, data, android, ios, goals, general
 */
public class IntentClassifier {

    public enum TaskCategory {
        CODING,
        TESTING,
        DEVOPS,
        GIT,
        DOCS,
        SECURITY,
        ARCHITECTURE,
        DATABASE,
        SYSADMIN,
        DATA,
        ANDROID,
        IOS,
        GOALS,
        GENERAL  // Fallback — routes to Coordinator
    }

    private static final Map<TaskCategory, List<Pattern>> PATTERNS = new LinkedHashMap<>();

    static {
        // Order matters: more specific categories checked first
        PATTERNS.put(TaskCategory.GIT, compilePatterns(
            "commit", "push", "pull", "merge", "branch", "git ", "revert", "cherry-pick",
            "tag", "stash", "diff", "log.*commit", "recent changes", "what changed"
        ));

        PATTERNS.put(TaskCategory.TESTING, compilePatterns(
            "test", "spec", "assert", "junit", "pytest", "jest", "mocha", "coverage",
            "e2e", "selenium", "qa", "verify", "validate", "regression", "benchmark"
        ));

        PATTERNS.put(TaskCategory.SECURITY, compilePatterns(
            "secur", "vulnerab", "audit", "cve", "injection", "xss", "csrf",
            "auth.*review", "penetration", "owasp", "hardcoded.*secret", "encrypt"
        ));

        PATTERNS.put(TaskCategory.DATABASE, compilePatterns(
            "sql", "query", "migration", "schema", "table", "index.*database",
            "database", "postgres", "mysql", "mongodb", "redis", "orm"
        ));

        PATTERNS.put(TaskCategory.DEVOPS, compilePatterns(
            "docker", "kubernetes", "k8s", "deploy", "ci/cd", "pipeline", "terraform",
            "helm", "aws", "gcp", "azure.*cloud", "infrastructure", "nginx", "monitor"
        ));

        PATTERNS.put(TaskCategory.ARCHITECTURE, compilePatterns(
            "architect", "design", "pattern", "refactor", "coupling", "cohesion",
            "microservice", "scalab", "performance.*review", "structure", "module",
            "dependency.*analys", "clean.*code", "solid",
            "analyz.*project", "analyz.*code", "analyz.*entire", "deep.*analyz",
            "review.*project", "review.*code", "review.*entire", "codebase.*review",
            "scan.*project", "scan.*code", "explore.*code", "understand.*code",
            "overview", "high.*level", "how.*work", "how.*structure"
        ));

        PATTERNS.put(TaskCategory.ANDROID, compilePatterns(
            "android", "kotlin.*ui", "jetpack", "compose", "gradle.*android",
            "activity", "fragment", "recyclerview", "room.*database"
        ));

        PATTERNS.put(TaskCategory.IOS, compilePatterns(
            "ios", "swift", "swiftui", "xcode", "cocoapods", "storyboard",
            "uikit", "core.*data", "app.*store"
        ));

        PATTERNS.put(TaskCategory.DOCS, compilePatterns(
            "readme", "document", "javadoc", "comment", "changelog", "api.*doc",
            "write.*doc", "update.*doc", "explain.*code"
        ));

        PATTERNS.put(TaskCategory.DATA, compilePatterns(
            "csv", "json.*data", "pandas", "numpy", "analyz.*data", "dataset",
            "statistics", "report", "visualization", "chart", "trend"
        ));

        PATTERNS.put(TaskCategory.GOALS, compilePatterns(
            "goal", "todo", "task.*track", "progress", "sprint", "backlog",
            "priorit", "milestone", "what.*remain", "what.*pending",
            "execute.*goal", "run.*goal", "do.*goal", "complete.*goal"
        ));

        PATTERNS.put(TaskCategory.SYSADMIN, compilePatterns(
            "run ", "execute", "build.*project", "install.*dep", "start.*server",
            "process", "disk", "memory.*usage", "environment", "command",
            "count.*file", "count.*line", "list.*file", "find.*file", "search.*file",
            "how.*many", "size.*of", "open.*terminal", "shell", "script",
            "clean", "compile", "package", "maven", "npm.*run", "gradle"
        ));

        PATTERNS.put(TaskCategory.CODING, compilePatterns(
            "implement", "code", "function", "class", "method", "api", "endpoint",
            "feature", "bug", "fix", "add", "create", "write.*code", "logic",
            "algorithm", "interface", "service", "controller", "model",
            "import", "library", "module",
            "edit.*file", "change.*file", "modify", "update.*code", "rename",
            "variable", "error", "exception", "null", "crash", "broken"
        ));
    }

    /**
     * Classify user input into a task category.
     * Returns the best matching category, or GENERAL if no match.
     */
    public TaskCategory classify(String input) {
        if (input == null || input.isEmpty()) return TaskCategory.GENERAL;
        
        String lower = input.toLowerCase().trim();
        int bestScore = 0;
        TaskCategory bestCategory = TaskCategory.GENERAL;

        for (Map.Entry<TaskCategory, List<Pattern>> entry : PATTERNS.entrySet()) {
            int score = 0;
            for (Pattern pattern : entry.getValue()) {
                if (pattern.matcher(lower).find()) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestCategory = entry.getKey();
            }
        }

        return bestCategory;
    }

    /**
     * Returns confidence (0.0-1.0) of the classification.
     * Higher = more keywords matched for the winning category.
     */
    public double classifyWithConfidence(String input) {
        if (input == null || input.isEmpty()) return 0.0;
        
        String lower = input.toLowerCase().trim();
        int bestScore = 0;
        int totalPatterns = 0;

        for (Map.Entry<TaskCategory, List<Pattern>> entry : PATTERNS.entrySet()) {
            int score = 0;
            for (Pattern pattern : entry.getValue()) {
                if (pattern.matcher(lower).find()) {
                    score++;
                }
            }
            totalPatterns = Math.max(totalPatterns, entry.getValue().size());
            bestScore = Math.max(bestScore, score);
        }

        if (totalPatterns == 0) return 0.0;
        return Math.min(1.0, (double) bestScore / 3.0); // 3+ matches = full confidence
    }

    private static List<Pattern> compilePatterns(String... keywords) {
        List<Pattern> patterns = new ArrayList<>();
        for (String keyword : keywords) {
            // Use substring matching (no word boundaries) for typo tolerance
            // "deploy" matches "deploye", "deploying", "missdeploy" etc.
            patterns.add(Pattern.compile(keyword.replace(" ", "\\s*"), 
                Pattern.CASE_INSENSITIVE));
        }
        return patterns;
    }
}
