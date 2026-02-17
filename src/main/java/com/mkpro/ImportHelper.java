package com.mkpro;

import com.mkpro.models.Goal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ImportHelper {

    public static void importLogs(Path file, ActionLogger logger) throws IOException {
        List<String> lines = Files.readAllLines(file);
        String role = "";
        String timestamp = "";
        StringBuilder content = new StringBuilder();
        
        // Regex for ### ROLE - TIMESTAMP
        // Example: ### USER - 2026-02-16T12:00:00.000
        // Match ### followed by anything until " - ", then anything until end
        Pattern headerPattern = Pattern.compile("^###\\s*(.+?)\\s*-\\s*(.+)$");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("### ")) {
                // If we have accumulated content, save the previous log
                if (!role.isEmpty()) {
                    logger.importLog(role, content.toString().trim(), timestamp);
                    content.setLength(0);
                }
                
                Matcher m = headerPattern.matcher(trimmed);
                if (m.find()) {
                    role = m.group(1).trim();
                    timestamp = m.group(2).trim();
                } else {
                    role = "UNKNOWN";
                    timestamp = "UNKNOWN"; 
                }
            } else if (trimmed.equals("---")) {
                 // Separator. Save current block.
                 if (!role.isEmpty()) {
                    logger.importLog(role, content.toString().trim(), timestamp);
                    content.setLength(0);
                    role = ""; // Reset
                 }
            } else {
                content.append(line).append("\n");
            }
        }
        // Save last log if any
        if (!role.isEmpty()) {
            logger.importLog(role, content.toString().trim(), timestamp);
        }
    }

    public static List<Goal> importGoals(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        List<Goal> roots = new ArrayList<>();
        Stack<GoalNode> stack = new Stack<>();
        
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            
            // Calculate indent: Number of spaces at start
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            
            String text = line.trim();
            // Remove markdown list markers
            if (text.startsWith("- ") || text.startsWith("* ")) {
                text = text.substring(2);
            }
            
            Goal goal = parseGoalLine(text);
            
            // Manage Hierarchy based on indentation
            // If current indent is <= stack top indent, pop until we find a parent
            while (!stack.isEmpty() && indent <= stack.peek().indent) {
                stack.pop();
            }
            
            if (stack.isEmpty()) {
                roots.add(goal);
            } else {
                stack.peek().goal.addSubGoal(goal);
            }
            
            stack.push(new GoalNode(goal, indent));
        }
        
        return roots;
    }
    
    private static class GoalNode {
        Goal goal;
        int indent;
        GoalNode(Goal g, int i) { goal = g; indent = i; }
    }
    
    private static Goal parseGoalLine(String text) {
        Goal.Status status = Goal.Status.PENDING;
        String desc = text;
        
        // Try to find status marker
        // e.g. ✅ **[COMPLETED]** Description
        
        if (text.contains("[COMPLETED]")) status = Goal.Status.COMPLETED;
        else if (text.contains("[IN_PROGRESS]")) status = Goal.Status.IN_PROGRESS;
        else if (text.contains("[FAILED]")) status = Goal.Status.FAILED;
        else if (text.contains("[PENDING]")) status = Goal.Status.PENDING;
        
        // Clean up description
        // Remove emoji and **[STATUS]**
        desc = desc.replaceAll("^[\\p{So}]\\s*", ""); // Remove leading emoji
        desc = desc.replaceAll("\\*\\*\\[.*?\\]\\*\\*", ""); // Remove **[STATUS]**
        desc = desc.trim();
        
        Goal g = new Goal(desc);
        g.setStatus(status);
        return g;
    }
}
