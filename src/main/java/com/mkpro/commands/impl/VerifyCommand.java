package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.events.AutoVerifyGate;
import com.mkpro.events.EditApprovalService;
import com.mkpro.ui.AnsiColors;

import java.util.Collection;
import java.util.Collections;

/**
 * CLI command to toggle or manually run the Self-Healing Auto-Verify Gate.
 * Usage: /verify [on|off|status|run]
 */
public class VerifyCommand implements Command {

    @Override
    public String getName() {
        return "verify";
    }

    @Override
    public String getDescription() {
        return "Toggle or run the self-healing Auto-Verify gate (/verify on|off|status|run)";
    }

    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        if (context == null) {
            System.out.println("Context is not available.");
            return;
        }

        String sub = (args != null && args.length > 1) ? args[1].trim().toLowerCase() : "status";

        switch (sub) {
            case "on":
            case "enable":
            case "true":
                context.setAutoVerify(true);
                System.out.println(AnsiColors.GREEN + "[Auto-Verify] Enabled. Modified source files will be automatically verified after each turn." + AnsiColors.RESET);
                break;

            case "off":
            case "disable":
            case "false":
                context.setAutoVerify(false);
                System.out.println(AnsiColors.YELLOW + "[Auto-Verify] Disabled." + AnsiColors.RESET);
                break;

            case "run":
                Collection<String> files = EditApprovalService.INSTANCE != null
                    ? EditApprovalService.INSTANCE.getTurnSnapshots().keySet()
                    : Collections.emptyList();
                System.out.println(AnsiColors.CYAN + "[Auto-Verify] Running verification check..." + AnsiColors.RESET);
                AutoVerifyGate.VerifyResult res = AutoVerifyGate.verifyFiles(context, files, true);
                if (res.passed()) {
                    System.out.println(AnsiColors.GREEN + "[Auto-Verify] " + res.summary() + AnsiColors.RESET);
                } else {
                    System.out.println(AnsiColors.RED + "[Auto-Verify] " + res.summary() + AnsiColors.RESET);
                    if (res.errorOutput() != null && !res.errorOutput().isBlank()) {
                        System.out.println(res.errorOutput());
                    }
                }
                break;

            case "status":
            default:
                System.out.println(AnsiColors.BOLD + "Auto-Verify Gate Status:" + AnsiColors.RESET);
                System.out.println("  Enabled:     " + (context.isAutoVerify() ? AnsiColors.GREEN + "ON" : AnsiColors.DIM + "OFF") + AnsiColors.RESET);
                System.out.println("  Last Result: " + (context.isLastVerifyPassed() ? AnsiColors.GREEN + "PASSED" : AnsiColors.RED + "FAILED") + AnsiColors.RESET
                    + " — " + context.getLastVerifySummary());
                System.out.println("  Usage:       /verify on | off | status | run");
                break;
        }
    }
}
