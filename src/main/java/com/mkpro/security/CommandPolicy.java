package com.mkpro.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

/**
 * CommandPolicy enforces allowlist-based shell command execution.
 * Replaces the old blacklist approach with a positive security model.
 * 
 * Configuration loaded from ~/.mkpro/command_policy.yaml with bundled defaults.
 */
public class CommandPolicy {

    /**
     * Result of a command policy check.
     */
    public static class PolicyResult {
        private final boolean allowed;
        private final String reason;

        private PolicyResult(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        public boolean isAllowed() { return allowed; }
        public String getReason() { return reason; }

        public static PolicyResult allow() { return new PolicyResult(true, null); }
        public static PolicyResult deny(String reason) { return new PolicyResult(false, reason); }
    }

    // Allowed command prefixes (the command must start with one of these)
    private final Set<String> allowedCommands;

    // Explicitly blocked argument patterns (applied after allowlist pass)
    private final List<Pattern> blockedArgPatterns;

    // Maximum command length to prevent abuse
    private final int maxCommandLength;

    private static volatile CommandPolicy instance;

    private CommandPolicy(Set<String> allowedCommands, List<Pattern> blockedArgPatterns, int maxCommandLength) {
        this.allowedCommands = Collections.unmodifiableSet(allowedCommands);
        this.blockedArgPatterns = Collections.unmodifiableList(blockedArgPatterns);
        this.maxCommandLength = maxCommandLength;
    }

    public static synchronized CommandPolicy getInstance() {
        if (instance == null) {
            instance = loadPolicy();
        }
        return instance;
    }

    /**
     * Reload policy from disk (useful for dynamic reconfiguration).
     */
    public static synchronized void reload() {
        instance = loadPolicy();
    }

    /**
     * Evaluate whether a command is allowed to execute.
     */
    public PolicyResult evaluate(String command) {
        if (command == null || command.isBlank()) {
            return PolicyResult.deny("Command cannot be null or empty");
        }

        String trimmed = command.trim();

        // Length check
        if (trimmed.length() > maxCommandLength) {
            return PolicyResult.deny("Command exceeds maximum length (" + maxCommandLength + " chars)");
        }

        // Normalize for matching: lowercase, collapse whitespace
        String normalized = trimmed.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");

        // Extract the base command (first token, or first token after common shells)
        String baseCommand = extractBaseCommand(normalized);

        // Check allowlist
        boolean allowed = false;
        for (String allowedCmd : allowedCommands) {
            if (baseCommand.equals(allowedCmd) || baseCommand.endsWith("/" + allowedCmd) || baseCommand.endsWith("\\" + allowedCmd)) {
                allowed = true;
                break;
            }
        }

        if (!allowed) {
            return PolicyResult.deny("Command '" + baseCommand + "' is not in the allowlist. " +
                "Allowed commands: " + allowedCommands);
        }

        // Check blocked argument patterns (prevents dangerous flags even on allowed commands)
        for (Pattern pattern : blockedArgPatterns) {
            if (pattern.matcher(normalized).find()) {
                return PolicyResult.deny("Command contains blocked pattern: " + pattern.pattern());
            }
        }

        return PolicyResult.allow();
    }

    /**
     * Extracts the base command from a full command string.
     * Handles pipes, chains, and shell wrappers.
     */
    private String extractBaseCommand(String normalized) {
        // If command contains chaining operators, check each segment
        // For the primary check, we take the first command
        String[] segments = normalized.split("[;&|]");
        String firstSegment = segments[0].trim();

        // Strip common shell prefixes (sudo, cmd /c, bash -c, etc.)
        String[] shellPrefixes = {"sudo", "cmd /c", "cmd.exe /c", "bash -c", "sh -c", "powershell -command", "pwsh -command"};
        for (String prefix : shellPrefixes) {
            if (firstSegment.startsWith(prefix + " ")) {
                firstSegment = firstSegment.substring(prefix.length()).trim();
                // Remove wrapping quotes if present
                if ((firstSegment.startsWith("\"") && firstSegment.endsWith("\"")) ||
                    (firstSegment.startsWith("'") && firstSegment.endsWith("'"))) {
                    firstSegment = firstSegment.substring(1, firstSegment.length() - 1).trim();
                }
                break;
            }
        }

        // Get first token as the command
        String[] tokens = firstSegment.split("\\s+", 2);
        String cmd = tokens[0];

        // Strip path prefix (e.g., /usr/bin/git -> git, C:\Program Files\git\git.exe -> git)
        if (cmd.contains("/")) {
            cmd = cmd.substring(cmd.lastIndexOf('/') + 1);
        }
        if (cmd.contains("\\")) {
            cmd = cmd.substring(cmd.lastIndexOf('\\') + 1);
        }
        // Strip .exe extension on Windows
        if (cmd.endsWith(".exe")) {
            cmd = cmd.substring(0, cmd.length() - 4);
        }

        return cmd;
    }

    /**
     * Check if a command contains chaining operators that could bypass policy.
     * Returns separate PolicyResult for chained commands.
     */
    public PolicyResult evaluateChained(String command) {
        if (command == null || command.isBlank()) {
            return PolicyResult.deny("Command cannot be null or empty");
        }

        // Split on shell chaining operators
        String[] segments = command.split("[;&|]+");
        for (String segment : segments) {
            String trimmed = segment.trim();
            if (!trimmed.isEmpty()) {
                PolicyResult result = evaluate(trimmed);
                if (!result.isAllowed()) {
                    return PolicyResult.deny("Chained command rejected: " + result.getReason());
                }
            }
        }
        return PolicyResult.allow();
    }

    public Set<String> getAllowedCommands() {
        return allowedCommands;
    }

    // --- Policy Loading ---

    private static CommandPolicy loadPolicy() {
        // Try loading from user config first
        Path userPolicyFile = Paths.get(System.getProperty("user.home"), ".mkpro", "command_policy.yaml");
        if (Files.exists(userPolicyFile)) {
            try {
                return loadFromYaml(Files.newInputStream(userPolicyFile));
            } catch (Exception e) {
                System.err.println("[CommandPolicy] Error loading user policy, using defaults: " + e.getMessage());
            }
        }
        return createDefaultPolicy();
    }

    @SuppressWarnings("unchecked")
    private static CommandPolicy loadFromYaml(InputStream is) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        Map<String, Object> config = mapper.readValue(is, Map.class);

        List<String> allowed = (List<String>) config.getOrDefault("allowed_commands", Collections.emptyList());
        List<String> blocked = (List<String>) config.getOrDefault("blocked_patterns", Collections.emptyList());
        int maxLen = (int) config.getOrDefault("max_command_length", 4096);

        Set<String> allowedSet = new LinkedHashSet<>(allowed);
        List<Pattern> patterns = new ArrayList<>();
        for (String p : blocked) {
            patterns.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE));
        }

        return new CommandPolicy(allowedSet, patterns, maxLen);
    }

    private static CommandPolicy createDefaultPolicy() {
        // Default allowlist: common dev tools
        Set<String> allowed = new LinkedHashSet<>(Arrays.asList(
            // Version control
            "git",
            // Build tools
            "mvn", "maven", "gradle", "gradlew", "gradlew.bat",
            // Node.js ecosystem
            "npm", "npx", "node", "yarn", "pnpm", "bun",
            // Python
            "python", "python3", "pip", "pip3", "poetry", "uv",
            // Java
            "java", "javac", "jar",
            // Rust
            "cargo", "rustc",
            // Go
            "go",
            // Docker & containers
            "docker", "docker-compose", "podman",
            // Kubernetes
            "kubectl", "helm",
            // Cloud CLIs
            "aws", "gcloud", "az",
            // Common utilities
            "ls", "dir", "cat", "type", "echo", "pwd", "cd",
            "find", "grep", "head", "tail", "wc", "sort", "uniq",
            "cp", "copy", "mv", "move", "mkdir", "touch",
            "curl", "wget",
            // Testing
            "pytest", "jest", "mocha", "vitest",
            // Linting & formatting
            "eslint", "prettier", "black", "ruff", "checkstyle",
            // System info (safe)
            "whoami", "hostname", "date", "env", "set",
            "netstat", "ping", "tracert", "traceroute", "nslookup"
        ));

        // Blocked patterns: dangerous flags/args even on allowed commands
        List<Pattern> blocked = new ArrayList<>(Arrays.asList(
            // Recursive force delete
            Pattern.compile("rm\\s+(-[a-z]*f[a-z]*\\s+-[a-z]*r|--force.*--recursive|-rf|-fr)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("del\\s+/[a-z]*f[a-z]*/[a-z]*s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("rd\\s+/s\\s+/q\\s+[a-z]:\\\\(windows|program)", Pattern.CASE_INSENSITIVE),
            // Format/wipe
            Pattern.compile("(mkfs|format\\s+[a-z]:)", Pattern.CASE_INSENSITIVE),
            // Dangerous redirections to system devices
            Pattern.compile(">\\s*/dev/sd[a-z]", Pattern.CASE_INSENSITIVE),
            // Fork bombs
            Pattern.compile(":\\(\\)\\{\\s*:\\|:\\s*&\\s*\\}\\s*;\\s*:", Pattern.CASE_INSENSITIVE),
            // Reverse shells
            Pattern.compile("(nc|ncat|netcat)\\s+.*-e", Pattern.CASE_INSENSITIVE),
            Pattern.compile("bash\\s+-i\\s+>&", Pattern.CASE_INSENSITIVE),
            // Force push to main/master without explicit confirmation path
            Pattern.compile("git\\s+push\\s+.*--force.*\\s+(main|master)", Pattern.CASE_INSENSITIVE),
            // Destructive git on main
            Pattern.compile("git\\s+branch\\s+-[dD]\\s+(main|master)", Pattern.CASE_INSENSITIVE)
        ));

        return new CommandPolicy(allowed, blocked, 4096);
    }
}
