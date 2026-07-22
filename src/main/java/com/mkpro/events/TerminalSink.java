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
            case EDIT_PROPOSAL -> {
                EditProposal proposal = event.getEditProposal();
                if (proposal != null) {
                    handleEditProposal(proposal);
                }
            }
            case EDIT_APPROVED -> {
                System.out.println(GREEN + "  ✓ [CodeEditor] Changes approved: " + event.get("path") + RESET);
            }
            case EDIT_REJECTED -> {
                System.out.println("\u001b[31m  ✗ [CodeEditor] Changes rejected: " + event.get("path") + RESET);
            }
            default -> {
                // Other event types not handled by terminal sink
            }
        }
    }

    /**
     * Display diff and run auto-approve timer (7s).
     * Runs on a daemon thread so it doesn't block the event bus dispatch.
     */
    private void handleEditProposal(EditProposal proposal) {
        Thread timerThread = new Thread(() -> {
            String BLUE = "\u001b[34m";
            String RED = "\u001b[31m";

            System.out.println(BLUE + "\n--- PROPOSED CHANGES FOR: " + proposal.getFilePath() + " ---" + RESET);

            if (proposal.isNewFile()) {
                System.out.println(YELLOW + "[CodeEditor] Creating NEW file" + RESET);
            }

            // Show diff
            for (EditProposal.DiffLine line : proposal.getDiffLines()) {
                switch (line.getType()) {
                    case ADDED -> System.out.println(GREEN + "+ " + line.getText() + RESET);
                    case REMOVED -> System.out.println(RED + "- " + line.getText() + RESET);
                    case INFO -> System.out.println(YELLOW + "  " + line.getText() + RESET);
                    default -> System.out.println("  " + line.getText());
                }
            }

            if (proposal.getDiffLines().isEmpty()) {
                System.out.println(YELLOW + "No textual changes detected." + RESET);
            }

            System.out.println(BLUE + "---------------------------------------------" + RESET);

            // Auto-approve timer
            EditApprovalService service = EditApprovalService.INSTANCE;
            if (service == null) return;

            for (int i = 7; i > 0; i--) {
                // Check if already resolved (web user might have approved)
                if (service.getProposal(proposal.getId()) == null) return;

                System.out.print("\r" + YELLOW + "Auto-approving in " + i + "s... (Press Enter to reject)   " + RESET);
                try {
                    if (System.in.available() > 0) {
                        // User interrupted — reject
                        System.out.println();
                        service.reject(proposal.getId());
                        return;
                    }
                    Thread.sleep(1000);
                } catch (Exception e) {
                    break;
                }
            }

            System.out.println("\r" + GREEN + "Auto-approving changes.                              " + RESET);
            // Auto-approve if still pending
            service.approve(proposal.getId());
        }, "edit-approval-timer");
        timerThread.setDaemon(true);
        timerThread.start();
    }
}
