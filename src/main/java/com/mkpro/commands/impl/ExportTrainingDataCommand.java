package com.mkpro.commands.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mkpro.ActionLogger;
import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.mkpro.MkPro.*;

/**
 * Exports actual chat session data from ActionLogger into JSONL training format.
 * Output is written to datajsonl/ folder in the project root.
 * 
 * Format per line:
 * {"messages": [{"role": "system", "content": "..."}, {"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}]}
 * 
 * Usage: /export [coordinator|goaltracker|all]
 */
public class ExportTrainingDataCommand implements Command {

    private static final ObjectMapper mapper = new ObjectMapper();

    // Matches log entries: [2026-07-07T15:30:00.123] ROLE: content
    private static final Pattern LOG_PATTERN = Pattern.compile(
        "^\\[([^\\]]+)\\]\\s+(\\w+):\\s+(.+)$", Pattern.DOTALL
    );

    private static final String COORDINATOR_SYSTEM = 
        "You are the Coordinator agent for mkpro. You orchestrate a team of specialized AI agents. " +
        "Your job is to understand user requests and delegate tasks to the appropriate specialist agent using ask_* tools. " +
        "You do not write code or run commands yourself. Available agents: GoalTracker, Coder, CodeEditor, SysAdmin, " +
        "GitAgent, Tester, DocWriter, SecurityAuditor, Architect, DatabaseAdmin, DevOps, DataAnalyst, AndroidDev, IosDev.";

    private static final String GOALTRACKER_SYSTEM = 
        "You are the GoalTracker agent for mkpro. Your role is to manage project goals and TODO items. " +
        "You create goals, decompose them into sub-goals, track progress, update statuses " +
        "(PENDING, IN_PROGRESS, COMPLETED, FAILED), and report on project progress. " +
        "You maintain a hierarchical goal tree.";

    private static final java.util.Map<String, String> AGENT_SYSTEMS = new java.util.LinkedHashMap<>();

    static {
        AGENT_SYSTEMS.put("Coordinator", COORDINATOR_SYSTEM);
        AGENT_SYSTEMS.put("GoalTracker", GOALTRACKER_SYSTEM);
        AGENT_SYSTEMS.put("Coder", "You are the Coder agent for mkpro. You read and analyze code, implement features, fix bugs, and write clean, tested code. You use file_read, codebase_search, and graph_memory tools.");
        AGENT_SYSTEMS.put("CodeEditor", "You are the CodeEditor agent for mkpro. You safely apply code changes to files with diff preview and user confirmation. You create backups before modifications.");
        AGENT_SYSTEMS.put("SysAdmin", "You are the SysAdmin agent for mkpro. You execute shell commands, manage infrastructure, run build tools (Maven, Gradle, npm). You are restricted from modifying code directly.");
        AGENT_SYSTEMS.put("GitAgent", "You are the GitAgent agent for mkpro. You manage version control: staging, committing, pushing, branching, and enforcing semantic commit messages.");
        AGENT_SYSTEMS.put("Tester", "You are the Tester agent for mkpro. You write unit and integration tests, run test suites, perform E2E testing, and validate code correctness.");
        AGENT_SYSTEMS.put("DocWriter", "You are the DocWriter agent for mkpro. You maintain README, generate documentation, write Javadocs/Docstrings, and keep docs in sync with code.");
        AGENT_SYSTEMS.put("SecurityAuditor", "You are the SecurityAuditor agent for mkpro. You scan code for vulnerabilities (SQLi, XSS, secrets), run audit tools, and recommend hardening steps.");
        AGENT_SYSTEMS.put("Architect", "You are the Architect agent for mkpro. You review high-level design, analyze cohesion/coupling, enforce patterns, plan refactoring, and store system designs in graph memory.");
        AGENT_SYSTEMS.put("DatabaseAdmin", "You are the DatabaseAdmin agent for mkpro. You write complex SQL queries, create schema migrations, and analyze database structures.");
        AGENT_SYSTEMS.put("DevOps", "You are the DevOps agent for mkpro. You write Dockerfiles, Kubernetes manifests, CI/CD configs, and interact with cloud CLIs (AWS, GCP).");
        AGENT_SYSTEMS.put("DataAnalyst", "You are the DataAnalyst agent for mkpro. You analyze datasets (CSV, JSON), write Python scripts (pandas, numpy) for statistical analysis, and generate insights.");
        AGENT_SYSTEMS.put("AndroidDev", "You are the AndroidDev agent for mkpro. Expert in Kotlin, Jetpack Compose, Android SDK, and Gradle-based Android projects.");
        AGENT_SYSTEMS.put("IosDev", "You are the IosDev agent for mkpro. Expert in Swift, SwiftUI, Xcode, and iOS frameworks.");
    }

    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        String target = args.length > 0 ? args[0].toLowerCase() : "all";

        Path outputDir = Paths.get("").toAbsolutePath().resolve("datajsonl");
        Files.createDirectories(outputDir);

        List<String> logs = ActionLogger.getLogs();
        if (logs.isEmpty()) {
            System.out.println(ANSI_YELLOW + "No session logs found to export." + ANSI_RESET);
            return;
        }

        System.out.println(ANSI_BLUE + "Parsing " + logs.size() + " log entries..." + ANSI_RESET);

        // Parse logs into structured entries
        List<LogEntry> entries = parseLogs(logs);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        int exported = 0;

        if ("all".equals(target)) {
            // Export all agents
            for (java.util.Map.Entry<String, String> agentEntry : AGENT_SYSTEMS.entrySet()) {
                String agentName = agentEntry.getKey();
                String systemPrompt = agentEntry.getValue();
                int count = exportAgentData(agentName, systemPrompt, entries, outputDir, timestamp);
                if (count > 0) {
                    System.out.println(ANSI_GREEN + "  " + agentName + ": " + count + " training examples" + ANSI_RESET);
                }
                exported += count;
            }
        } else {
            // Export specific agent
            String agentName = target.substring(0, 1).toUpperCase() + target.substring(1);
            String systemPrompt = AGENT_SYSTEMS.get(agentName);
            if (systemPrompt == null) {
                System.out.println(ANSI_YELLOW + "Unknown agent: " + target + ". Available: " + 
                    String.join(", ", AGENT_SYSTEMS.keySet()) + ANSI_RESET);
                return;
            }
            int count = exportAgentData(agentName, systemPrompt, entries, outputDir, timestamp);
            System.out.println(ANSI_GREEN + "  " + agentName + ": " + count + " training examples" + ANSI_RESET);
            exported += count;
        }

        if (exported == 0) {
            System.out.println(ANSI_YELLOW + "No conversation pairs found in logs. Chat more and try again." + ANSI_RESET);
        } else {
            System.out.println(ANSI_GREEN + "Exported " + exported + " total training examples to datajsonl/" + ANSI_RESET);
        }
    }

    /**
     * Generic agent export: finds USER→Agent response pairs in the logs.
     */
    private int exportAgentData(String agentName, String systemPrompt, List<LogEntry> entries, Path outputDir, String timestamp) throws IOException {
        Path file = outputDir.resolve(agentName.toLowerCase() + "_session_" + timestamp + ".jsonl");
        List<ObjectNode> trainingLines = new ArrayList<>();

        if ("Coordinator".equalsIgnoreCase(agentName)) {
            // Coordinator: USER→Coordinator pairs
            for (int i = 0; i < entries.size() - 1; i++) {
                LogEntry current = entries.get(i);
                if (isUserEntry(current)) {
                    for (int j = i + 1; j < entries.size(); j++) {
                        LogEntry next = entries.get(j);
                        if ("Coordinator".equalsIgnoreCase(next.role)) {
                            trainingLines.add(createTrainingLine(systemPrompt, current.content, next.content));
                            break;
                        }
                        if (isUserEntry(next)) break;
                    }
                }
            }
        } else {
            // Other agents: find entries logged as this agent name
            for (int i = 0; i < entries.size(); i++) {
                LogEntry entry = entries.get(i);
                if (agentName.equalsIgnoreCase(entry.role)) {
                    // Find the preceding user input that triggered this
                    String userContent = findPrecedingUserInput(entries, i);
                    if (userContent != null && !entry.content.trim().isEmpty()) {
                        trainingLines.add(createTrainingLine(systemPrompt, userContent, entry.content));
                    }
                }
            }
        }

        // Also extract from delegation patterns in SYSTEM logs
        for (int i = 0; i < entries.size() - 1; i++) {
            LogEntry entry = entries.get(i);
            if ("SYSTEM".equalsIgnoreCase(entry.role) && entry.content.contains("Delegating task to " + agentName)) {
                for (int j = i + 1; j < entries.size(); j++) {
                    LogEntry next = entries.get(j);
                    if (agentName.equalsIgnoreCase(next.role)) {
                        String userContent = findPrecedingUserInput(entries, i);
                        if (userContent != null) {
                            trainingLines.add(createTrainingLine(systemPrompt, userContent, next.content));
                        }
                        break;
                    }
                    if (isUserEntry(next)) break;
                }
            }
        }

        if (!trainingLines.isEmpty()) {
            writeJsonl(file, trainingLines);
        }
        return trainingLines.size();
    }

    private List<LogEntry> parseLogs(List<String> rawLogs) {
        List<LogEntry> entries = new ArrayList<>();
        StringBuilder currentContent = new StringBuilder();
        String currentRole = null;
        String currentTimestamp = null;

        for (String line : rawLogs) {
            Matcher m = LOG_PATTERN.matcher(line);
            if (m.matches()) {
                // Save previous entry
                if (currentRole != null) {
                    entries.add(new LogEntry(currentTimestamp, currentRole, currentContent.toString().trim()));
                }
                currentTimestamp = m.group(1);
                currentRole = m.group(2);
                currentContent = new StringBuilder(m.group(3));
            } else {
                // Continuation line
                if (currentContent != null) {
                    currentContent.append("\n").append(line);
                }
            }
        }
        // Save last entry
        if (currentRole != null) {
            entries.add(new LogEntry(currentTimestamp, currentRole, currentContent.toString().trim()));
        }

        return entries;
    }

    private boolean isUserEntry(LogEntry entry) {
        String role = entry.role.toLowerCase();
        return "user".equals(role);
    }

    private String findPrecedingUserInput(List<LogEntry> entries, int beforeIndex) {
        for (int j = beforeIndex - 1; j >= Math.max(0, beforeIndex - 10); j--) {
            if (isUserEntry(entries.get(j))) {
                return entries.get(j).content;
            }
        }
        return null;
    }

    private ObjectNode createTrainingLine(String systemPrompt, String userContent, String assistantContent) {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode messages = mapper.createArrayNode();

        ObjectNode system = mapper.createObjectNode();
        system.put("role", "system");
        system.put("content", systemPrompt);
        messages.add(system);

        ObjectNode user = mapper.createObjectNode();
        user.put("role", "user");
        user.put("content", userContent);
        messages.add(user);

        ObjectNode assistant = mapper.createObjectNode();
        assistant.put("role", "assistant");
        assistant.put("content", assistantContent);
        messages.add(assistant);

        root.set("messages", messages);
        return root;
    }

    private void writeJsonl(Path file, List<ObjectNode> lines) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file)) {
            for (ObjectNode line : lines) {
                writer.write(mapper.writeValueAsString(line));
                writer.newLine();
            }
        }
    }

    @Override
    public String getName() {
        return "export";
    }

    @Override
    public String getDescription() {
        return "Export chat session data as JSONL training data to datajsonl/ folder. Usage: /export [agent-name|all]";
    }

    private static class LogEntry {
        final String timestamp;
        final String role;
        final String content;

        LogEntry(String timestamp, String role, String content) {
            this.timestamp = timestamp;
            this.role = role;
            this.content = content;
        }
    }
}
