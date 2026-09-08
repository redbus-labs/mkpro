package com.mkpro.commands.impl;

import com.mkpro.CentralMemory;
import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.core.StoreKeys;
import com.mkpro.plugins.FilterConfig;

import static com.mkpro.MkPro.*;

/**
 * /compact command — Configures SmartEventFilterPlugin rules and invocation turn limits,
 * and performs context & storage compaction.
 *
 * Usage:
 *   /compact                 - Compact active context window and run compaction
 *   /compact [turns]         - Set maximum invocation turns (e.g. /compact 10 or /compact off)
 *   /compact filter / status - Prints detailed rules and stats table
 *   /compact prune <chars|off> - e.g. /compact prune 1500 or /compact prune off
 *   /compact churn <on|off>  - e.g. /compact churn on or /compact churn off
 */
public class CompactCommand implements Command {

    @Override
    public String getName() {
        return "compact";
    }

    @Override
    public String getDescription() {
        return "Compact context window turns, manage SmartEventFilterPlugin rules, and storage compaction.";
    }

    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        FilterConfig fc = context.getFilterConfig();
        if (fc == null) {
            fc = new FilterConfig();
            context.setFilterConfig(fc);
        }

        if (args != null && args.length > 0) {
            String sub = args[0].toLowerCase();
            if (sub.equals("filter") || sub.equals("status")) {
                printFilterStatus(fc, context);
                return;
            } else if (sub.equals("prune")) {
                if (args.length < 2) {
                    System.out.println(ANSI_YELLOW + "Usage: /compact prune <chars|off>" + ANSI_RESET);
                    return;
                }
                String val = args[1].toLowerCase();
                if (val.equals("off") || val.equals("false") || val.equals("0")) {
                    fc.setPruneToolOutputs(false);
                    System.out.println(ANSI_GREEN + "✔ Tool output pruning disabled." + ANSI_RESET);
                } else {
                    try {
                        int chars = Integer.parseInt(val);
                        fc.setPruneToolOutputs(true);
                        fc.setMaxToolPayloadChars(chars);
                        System.out.println(ANSI_GREEN + "✔ Tool output pruning enabled: max " + chars + " chars per payload." + ANSI_RESET);
                    } catch (NumberFormatException e) {
                        System.out.println(ANSI_RED + "Invalid number: " + args[1] + ANSI_RESET);
                        return;
                    }
                }
                saveFilterConfig(fc);
                rebuildRunnerWithConfig(context, fc);
                return;
            } else if (sub.equals("churn")) {
                if (args.length < 2) {
                    System.out.println(ANSI_YELLOW + "Usage: /compact churn <on|off>" + ANSI_RESET);
                    return;
                }
                String val = args[1].toLowerCase();
                if (val.equals("on") || val.equals("true") || val.equals("1")) {
                    fc.setEvictStaleToolChurn(true);
                    System.out.println(ANSI_GREEN + "✔ Stale tool churn eviction enabled." + ANSI_RESET);
                } else if (val.equals("off") || val.equals("false") || val.equals("0")) {
                    fc.setEvictStaleToolChurn(false);
                    System.out.println(ANSI_GREEN + "✔ Stale tool churn eviction disabled." + ANSI_RESET);
                } else {
                    System.out.println(ANSI_RED + "Invalid value for churn (expected on/off)." + ANSI_RESET);
                    return;
                }
                saveFilterConfig(fc);
                rebuildRunnerWithConfig(context, fc);
                return;
            } else {
                // Number turns or "off"
                if (sub.equals("off") || sub.equals("none") || sub.equals("0") || sub.equals("-1")) {
                    fc.setMaxTurns(-1);
                    context.setMaxTurns(-1);
                    saveFilterConfig(fc);
                    rebuildRunnerWithConfig(context, fc);
                    System.out.println(ANSI_GREEN + "✔ Context compaction disabled: retaining full session history." + ANSI_RESET);
                    return;
                } else {
                    try {
                        int limit = Integer.parseInt(sub);
                        if (limit > 0) {
                            fc.setMaxTurns(limit);
                            context.setMaxTurns(limit);
                            saveFilterConfig(fc);
                            rebuildRunnerWithConfig(context, fc);
                            System.out.println(ANSI_GREEN + "✔ Context compaction set: keeping last " + limit + " invocation turns in active context." + ANSI_RESET);
                            return;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }

        // Default compaction run
        int currentTurns = fc.getMaxTurns();
        System.out.println(ANSI_CYAN + "Compacting session context and memory caches..." + ANSI_RESET);
        saveFilterConfig(fc);
        rebuildRunnerWithConfig(context, fc);
        if (currentTurns > 0) {
            System.out.println("• Active context turn window: " + currentTurns + " invocations");
        } else {
            System.out.println("• Active context turn window: full session (unlimited)");
        }

        System.gc();
        System.out.println(ANSI_GREEN + "✔ Compaction complete." + ANSI_RESET);
    }

    private void saveFilterConfig(FilterConfig fc) {
        try {
            CentralMemory.getInstance().put(StoreKeys.FILTER_CONFIG, fc);
        } catch (Exception ignored) {}
    }

    private void rebuildRunnerWithConfig(MkProContext context, FilterConfig fc) {
        if (context.getAgentManager() != null && context.getAgentConfigs() != null) {
            context.setRunner(context.getAgentManager().createRunner(context.getAgentConfigs(), "", fc));
        }
    }

    private void printFilterStatus(FilterConfig fc, MkProContext context) {
        System.out.println(ANSI_BOLD + ANSI_CYAN + "╔══════════════════════════════════════════════════════════════╗" + ANSI_RESET);
        System.out.println(ANSI_BOLD + ANSI_CYAN + "║               Smart Event Filter Status & Rules              ║" + ANSI_RESET);
        System.out.println(ANSI_BOLD + ANSI_CYAN + "╚══════════════════════════════════════════════════════════════╝" + ANSI_RESET);

        System.out.println(String.format(" %-30s │ %s", ANSI_BOLD + "Rule / Setting" + ANSI_RESET, ANSI_BOLD + "Value / Status" + ANSI_RESET));
        System.out.println("────────────────────────────────┼──────────────────────────────");
        System.out.println(String.format(" Max Turns Window               │ %s", fc.getMaxTurns() > 0 ? fc.getMaxTurns() + " turns" : "Disabled (Unlimited)"));
        System.out.println(String.format(" Prune Tool Outputs             │ %s", fc.isPruneToolOutputs() ? ANSI_GREEN + "Enabled" + ANSI_RESET + " (max " + fc.getMaxToolPayloadChars() + " chars)" : ANSI_YELLOW + "Disabled" + ANSI_RESET));
        System.out.println(String.format(" Evict Stale Tool Churn         │ %s", fc.isEvictStaleToolChurn() ? ANSI_GREEN + "Enabled" + ANSI_RESET : ANSI_YELLOW + "Disabled" + ANSI_RESET));
        System.out.println(String.format(" Pin Initial Prompt (Turn 0)    │ %s", fc.isPinInitialPrompt() ? ANSI_GREEN + "Yes" + ANSI_RESET : ANSI_RED + "No" + ANSI_RESET));
        System.out.println(String.format(" Pin Memory Events              │ %s", fc.isPinMemoryEvents() ? ANSI_GREEN + "Yes" + ANSI_RESET : ANSI_RED + "No" + ANSI_RESET));
        System.out.println("────────────────────────────────┴──────────────────────────────");
        System.out.println(ANSI_DIM + " Subcommands: /compact [turns|off], /compact prune <chars|off>," + ANSI_RESET);
        System.out.println(ANSI_DIM + "              /compact churn <on|off>, /compact filter" + ANSI_RESET);
    }
}
