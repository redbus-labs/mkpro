package com.mkpro.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MarkovTrainer builds and updates the MarkovRouter's transition matrix from JSONL training data.
 * 
 * Reads JSONL files from datajsonl/ directory and extracts:
 * - User message → classified into TaskCategory via IntentClassifier
 * - Assistant response → parsed to identify which agent was delegated to
 * 
 * The trainer can run incrementally — new JSONL files add to the existing matrix.
 */
public class MarkovTrainer {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final IntentClassifier classifier = new IntentClassifier();

    // Pattern to extract agent name from delegation responses
    // Matches: "Calling ask_coder", "ask_sys_admin", etc.
    private static final Pattern DELEGATION_PATTERN = Pattern.compile(
        "(?:Calling |\\[Calling )?ask_([a-z_]+)", Pattern.CASE_INSENSITIVE);
    
    // Match "delegate to the Architect", "I'll route this to the Coder"
    private static final Pattern AGENT_NAME_PATTERN = Pattern.compile(
        "(?:delegate|route|Delegating) (?:this )?(?:to the |to )([A-Z][a-zA-Z]+)", Pattern.CASE_INSENSITIVE);
    
    // Match ">> Delegating to SysAdmin..." (real session output from ActionLogger)
    private static final Pattern DIRECT_DELEGATION_PATTERN = Pattern.compile(
        ">> Delegating to ([A-Za-z]+)", Pattern.CASE_INSENSITIVE);

    /**
     * Train the router from all JSONL files in a directory.
     * Returns the number of examples processed.
     */
    public static int trainFromDirectory(MarkovRouter router, Path directory) {
        if (!Files.isDirectory(directory)) return 0;

        int total = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.jsonl")) {
            for (Path file : stream) {
                total += trainFromFile(router, file);
            }
        } catch (IOException e) {
            System.err.println("[MarkovTrainer] Error reading directory: " + e.getMessage());
        }
        return total;
    }

    /**
     * Train from a single JSONL file.
     */
    public static int trainFromFile(MarkovRouter router, Path file) {
        int count = 0;
        String lastAgent = null;

        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    JsonNode root = mapper.readTree(line);
                    JsonNode messages = root.get("messages");
                    if (messages == null || !messages.isArray()) continue;

                    String userMessage = null;
                    String assistantMessage = null;

                    for (JsonNode msg : messages) {
                        String role = msg.has("role") ? msg.get("role").asText() : "";
                        String content = msg.has("content") ? msg.get("content").asText() : "";
                        
                        if ("user".equals(role)) userMessage = content;
                        if ("assistant".equals(role)) assistantMessage = content;
                    }

                    if (userMessage != null && assistantMessage != null) {
                        // Classify the user's intent
                        IntentClassifier.TaskCategory category = classifier.classify(userMessage);

                        // Extract which agent was selected
                        String selectedAgent = extractAgent(assistantMessage);

                        if (selectedAgent != null && category != IntentClassifier.TaskCategory.GENERAL) {
                            router.recordTransition(category, lastAgent, selectedAgent);
                            lastAgent = selectedAgent;
                            count++;
                        }
                    }
                } catch (Exception e) {
                    // Skip malformed lines
                }
            }
        } catch (IOException e) {
            System.err.println("[MarkovTrainer] Error reading file " + file + ": " + e.getMessage());
        }

        return count;
    }

    /**
     * Extract the agent name from an assistant response.
     */
    static String extractAgent(String response) {
        if (response == null) return null;

        // Try tool-call pattern: "ask_coder", "ask_sys_admin"
        Matcher m = DELEGATION_PATTERN.matcher(response);
        if (m.find()) {
            return normalizeAgentName(m.group(1));
        }

        // Try direct delegation pattern: ">> Delegating to SysAdmin..."
        m = DIRECT_DELEGATION_PATTERN.matcher(response);
        if (m.find()) {
            return normalizeAgentName(m.group(1));
        }

        // Try natural language pattern: "delegate to the Architect"
        m = AGENT_NAME_PATTERN.matcher(response);
        if (m.find()) {
            return normalizeAgentName(m.group(1));
        }

        return null;
    }

    /**
     * Train the Maker completion patterns from maker_sequences.jsonl.
     * Format: {"category": "CODING", "agents": [...], "tools": [...], "turns": 4, "success": true}
     */
    public static int trainMakerSequences(MarkovRouter router, java.nio.file.Path file) {
        if (!java.nio.file.Files.exists(file)) return 0;
        int count = 0;

        try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(line);
                    String categoryStr = root.has("category") ? root.get("category").asText() : null;
                    boolean success = root.has("success") && root.get("success").asBoolean();
                    int turns = root.has("turns") ? root.get("turns").asInt() : 1;

                    if (categoryStr == null) continue;

                    IntentClassifier.TaskCategory category;
                    try {
                        category = IntentClassifier.TaskCategory.valueOf(categoryStr);
                    } catch (IllegalArgumentException e) {
                        continue;
                    }

                    // Extract tools list
                    java.util.List<String> tools = new java.util.ArrayList<>();
                    if (root.has("tools") && root.get("tools").isArray()) {
                        for (var node : root.get("tools")) {
                            tools.add(node.asText());
                        }
                    }

                    router.recordCompletion(category, tools, success, turns);
                    count++;
                } catch (Exception e) {
                    // Skip malformed lines
                }
            }
        } catch (Exception e) {
            System.err.println("[MarkovTrainer] Error reading maker sequences: " + e.getMessage());
        }
        return count;
    }

    /**
     * Normalize tool-name format to agent display name.
     */
    static String normalizeAgentName(String raw) {
        if (raw == null) return null;
        raw = raw.toLowerCase().trim();

        // Remove "ask_" prefix if present
        if (raw.startsWith("ask_")) raw = raw.substring(4);

        // Map known patterns
        switch (raw) {
            case "coder": case "developer": return "Coder";
            case "code_editor": case "codeeditor": return "CodeEditor";
            case "sys_admin": case "sysadmin": return "SysAdmin";
            case "git_agent": case "gitagent": return "GitAgent";
            case "tester": case "qa": return "Tester";
            case "doc_writer": case "docwriter": return "DocWriter";
            case "security_auditor": case "securityauditor": return "SecurityAuditor";
            case "architect": return "Architect";
            case "database_admin": case "databaseadmin": case "dba": return "DatabaseAdmin";
            case "dev_ops": case "devops": return "DevOps";
            case "data_analyst": case "dataanalyst": return "DataAnalyst";
            case "android_dev": case "androiddev": return "AndroidDev";
            case "ios_dev": case "iosdev": return "IosDev";
            case "goal_tracker": case "goaltracker": return "GoalTracker";
            case "coordinator": return "Coordinator";
            default:
                // CamelCase it: first letter upper
                if (!raw.isEmpty()) {
                    return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
                }
                return raw;
        }
    }
}
