package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.models.RunnerType;

public class RunnerCommand implements Command {
    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        if (args.length == 0) {
            System.out.println("Current runner type: " + (context.getCurrentRunnerType() != null ? context.getCurrentRunnerType().get() : "None"));
            return;
        }

        if (args[0].equalsIgnoreCase("switch") && args.length >= 2) {
            try {
                RunnerType type = RunnerType.valueOf(args[1].toUpperCase());

                // Warn about session reset
                System.out.println("\n\u001b[31;1mWARNING: Switching runner type will terminate the current session and all history will be lost!\u001b[0m");
                String input = context.getLineReader().readLine("Are you sure you want to proceed? (y/N): ").trim().toLowerCase();
                if (!input.equals("y") && !input.equals("yes")) {
                    System.out.println("Switch runner aborted.");
                    return;
                }

                context.getCurrentRunnerType().set(type);
                context.rebuildRunner(true); // Full storage rebuild when switching runner type
                System.out.println("\n\u001b[32mSwitched and rebuilt active runner with storage type: " + type + "\u001b[0m");
            } catch (IllegalArgumentException e) {
                System.out.println("Invalid runner type. Available: IN_MEMORY, MAP_DB, POSTGRES");
            }
        } else {
            System.out.println("Usage: runner [switch <type>]");
        }
    }

    @Override
    public String getName() {
        return "runner";
    }

    @Override
    public String getDescription() {
        return "Switch or view the current runner type.";
    }
}