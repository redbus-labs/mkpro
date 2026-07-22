package com.mkpro.events;

/**
 * Terminal sink — formats MkProEvents as ANSI-colored output to System.out.
 * Handles routing and maker events for CLI display.
 */
public class TerminalSink implements MkProEventListener {

    private static final String CYAN = "\u001b[36m";
    private static final String DIM = "\u001b[90m";
    private static final String PURPLE = "\u001b[35m";
    private static final String GREEN = "\u001b[32m";
    private static final String YELLOW = "\u001b[33m";
    private static final String RESET = "\u001b[0m";

    @Override
    public void onEvent(MkProEvent event) {
        switch (event.getType()) {
            case ROUTING_DECISION -> {
                System.out.println(CYAN + "[Fast-route → " + event.get("agent") +
                    " (" + event.get("confidence") + "% confidence, category: " + event.get("category") + ")]" + RESET);
            }
            case ROUTING_BELOW -> {
                System.out.println(DIM + "[Markov: " + event.get("agent") +
                    " " + event.get("confidence") + "% — below threshold, using Coordinator]" + RESET);
            }
            case ROUTING_INACTIVE -> {
                System.out.println(DIM + "[Markov: inactive (" + event.get("observations") +
                    " obs, need 20+). Run /train]" + RESET);
            }
            case ROUTING_KEYWORDS -> {
                System.out.println(CYAN + "[Fast-route → " + event.get("agent") +
                    " (YAML routing_keywords match)]" + RESET);
            }
            case MAKER_THOUGHT -> {
                System.out.println(PURPLE + "  [Maker] " + event.get("action") +
                    ": " + event.get("reason") + RESET);
            }
            case MAKER_GOAL -> {
                System.out.println(PURPLE + "  [Maker] New goal: \"" + event.get("goal") + "\"" + RESET);
            }
            case MAKER_COMPLETE -> {
                System.out.println(GREEN + "  [Maker] ✓ Goal completed: \"" + event.get("goal") + "\"" + RESET);
            }
            default -> {
                // Other event types not handled by terminal sink in Phase 1
            }
        }
    }
}
