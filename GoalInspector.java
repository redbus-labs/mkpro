package com.mkpro;  
import com.mkpro.models.Goal;  
import java.util.List;  
import java.nio.file.Paths;  
public class GoalInspector {  
    public static void main(String[] args) {  
        CentralMemory memory = new CentralMemory();  
        String projectPath = Paths.get("").toAbsolutePath().toString();  
        System.out.println("Project Path: " + projectPath);  
        List<Goal> goals = memory.getGoals(projectPath);  
        if (goals.isEmpty()) {  
            System.out.println("No goals found.");  
        } else {  
            for (Goal goal : goals) {  
                printGoal(goal, 0);  
            }  
        }  
    }  
    private static void printGoal(Goal goal, int depth) {  
        String indent = "  ".repeat(depth);  
        System.out.println(indent + "- [" + goal.getStatus() + "] " + goal.getDescription());  
        for (Goal sub : goal.getSubGoals()) {  
            printGoal(sub, depth + 1);  
        }  
    }  
} 
