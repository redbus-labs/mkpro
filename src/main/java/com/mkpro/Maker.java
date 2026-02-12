package com.mkpro;

import com.mkpro.models.Goal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class Maker {

    private static final List<String> BLACKLIST = Arrays.asList(
        "rm -rf /",
        "rm -fr /",
        ":(){:|:&};:",
        "mkfs",
        "> /dev/sd",
        "shutdown",
        "reboot",
        "format c:",
        "rd /s /q c:\\windows",
        "del /f /s /q c:\\windows",
        "nc -e",
        "bash -i >&"
    );

    public static final boolean isAllowed(String command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        String normalized = command.toLowerCase();
        for (String blacklisted : BLACKLIST) {
            if (normalized.contains(blacklisted)) {
                return false;
            }
        }
        return true;
    }

    public static void validateGoals(CentralMemory memory, String projectPath) {
        // Validation logic moved to areGoalsPending. Printing removed.
    }

    public static boolean areGoalsPending(CentralMemory memory, String projectPath) {
        if (memory == null || projectPath == null) {
            return false;
        }

        List<Goal> goals = memory.getGoals(projectPath);
        
        if (goals == null || goals.isEmpty()) {
            return false;
        }

        for (Goal goal : goals) {
            if (goal.getStatus() != Goal.Status.COMPLETED) {
                return true;
            }
        }

        return false;
    }

    public static String getGoalStimulus(CentralMemory memory, String projectPath) {
        if (memory == null || projectPath == null) {
            return "Error: Memory or Project Path is null.";
        }

        List<Goal> goals = memory.getGoals(projectPath);
        if (goals == null || goals.isEmpty()) {
            return "No goals defined for this project.";
        }

        List<StimulusItem> items = new ArrayList<>();
        collectStimulusItems(goals, "", items);

        if (items.isEmpty()) {
            return "All goals are currently marked as COMPLETED.";
        }

        // Sort by Priority: FAILED (1) > IN_PROGRESS (2) > PENDING (3)
        items.sort(Comparator.comparingInt(item -> getStatusScore(item.status)));

        StringBuilder sb = new StringBuilder();
        sb.append("GOAL STIMULUS REPORT:\n");

        boolean hasFailed = false;
        boolean hasInProgress = false;
        boolean hasPending = false;

        // Check presence of categories
        for (StimulusItem item : items) {
             if (item.status == Goal.Status.FAILED) hasFailed = true;
             else if (item.status == Goal.Status.IN_PROGRESS) hasInProgress = true;
             else if (item.status == Goal.Status.PENDING) hasPending = true;
        }

        // Build the Report
        if (hasFailed) {
            sb.append("\n[CRITICAL ATTENTION REQUIRED - FAILED]\n");
            for (StimulusItem item : items) {
                if (item.status == Goal.Status.FAILED) {
                    sb.append("! ").append(item.path).append("\n");
                }
            }
        }

        if (hasInProgress) {
            sb.append("\n[CURRENT FOCUS - IN PROGRESS]\n");
            for (StimulusItem item : items) {
                if (item.status == Goal.Status.IN_PROGRESS) {
                    sb.append("> ").append(item.path).append("\n");
                }
            }
        }

        if (hasPending) {
            sb.append("\n[UPCOMING TASKS - PENDING]\n");
            int count = 0;
            // Only show top 5 pending tasks to avoid context window flooding
            for (StimulusItem item : items) {
                if (item.status == Goal.Status.PENDING) {
                    sb.append("- ").append(item.path).append("\n");
                    count++;
                    if (count >= 5) {
                        sb.append("  ... (and more)\n");
                        break;
                    }
                }
            }
        }

        return sb.toString();
    }

    private static int getStatusScore(Goal.Status status) {
        switch (status) {
            case FAILED: return 1;
            case IN_PROGRESS: return 2;
            case PENDING: return 3;
            default: return 4;
        }
    }

    private static void collectStimulusItems(List<Goal> goals, String parentPath, List<StimulusItem> collector) {
        for (Goal goal : goals) {
            if (goal.getStatus() == Goal.Status.COMPLETED) continue;

            String currentPath = parentPath.isEmpty() 
                ? goal.getDescription() 
                : parentPath + " > " + goal.getDescription();
            
            boolean isEffectiveLeaf = (goal.getSubGoals() == null || goal.getSubGoals().isEmpty());
            
            if (!isEffectiveLeaf) {
                boolean allChildrenDone = true;
                for (Goal child : goal.getSubGoals()) {
                    if (child.getStatus() != Goal.Status.COMPLETED) {
                        allChildrenDone = false;
                        break;
                    }
                }
                if (allChildrenDone) isEffectiveLeaf = true;
            }

            if (isEffectiveLeaf) {
                collector.add(new StimulusItem(goal.getStatus(), currentPath));
            } else {
                collectStimulusItems(goal.getSubGoals(), currentPath, collector);
            }
        }
    }

    private static class StimulusItem {
        Goal.Status status;
        String path;

        StimulusItem(Goal.Status status, String path) {
            this.status = status;
            this.path = path;
        }
    }
}
