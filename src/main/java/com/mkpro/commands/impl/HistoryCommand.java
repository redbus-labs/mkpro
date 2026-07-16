package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;

import java.util.List;

import static com.mkpro.MkPro.*;

/**
 * Shows previous chat history from the current session.
 * 
 * Usage:
 *   /history         - Show last 10 exchanges
 *   /history N       - Show last N exchanges
 *   /history all     - Show entire session
 *   /history new     - Start a fresh session (creates new, keeps old)
 */
public class HistoryCommand implements Command {

    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        Session session = context.getCurrentSession();
        if (session == null) {
            System.out.println(ANSI_YELLOW + "No active session." + ANSI_RESET);
            return;
        }

        String subCommand = args.length > 0 ? args[0].toLowerCase() : "10";

        if ("new".equals(subCommand) || "clear".equals(subCommand)) {
            startNewSession(context);
            return;
        }

        int limit;
        if ("all".equals(subCommand)) {
            limit = Integer.MAX_VALUE;
        } else {
            try {
                limit = Integer.parseInt(subCommand);
            } catch (NumberFormatException e) {
                limit = 10;
            }
        }

        showHistory(session, limit);
    }

    private void showHistory(Session session, int limit) {
        List<Event> events = session.events();
        if (events == null || events.isEmpty()) {
            System.out.println(ANSI_YELLOW + "No history in current session." + ANSI_RESET);
            return;
        }

        // Collect displayable messages (user + model responses with text)
        java.util.List<String[]> messages = new java.util.ArrayList<>();
        for (Event event : events) {
            if (event.content().isEmpty()) continue;
            
            StringBuilder textBuilder = new StringBuilder();
            event.content().get().parts().ifPresent(parts -> {
                for (com.google.genai.types.Part part : parts) {
                    part.text().ifPresent(textBuilder::append);
                }
            });
            String text = textBuilder.toString();
            if (text.isBlank()) continue;

            String author = event.author();
            String role;
            if ("user".equals(author)) {
                role = "You";
            } else {
                role = author != null ? author : "AI";
            }
            messages.add(new String[]{role, text.trim()});
        }

        if (messages.isEmpty()) {
            System.out.println(ANSI_YELLOW + "No displayable messages in session." + ANSI_RESET);
            return;
        }

        // Show last N messages
        int start = Math.max(0, messages.size() - limit);
        int total = messages.size();

        System.out.println(ANSI_CYAN + "\n── Chat History (" + (total - start) + " of " + total + " messages) ──" + ANSI_RESET);

        for (int i = start; i < messages.size(); i++) {
            String role = messages.get(i)[0];
            String text = messages.get(i)[1];

            if ("You".equals(role)) {
                System.out.println();
                System.out.println(ANSI_GREEN + "  You: " + ANSI_RESET + truncate(text, 200));
            } else {
                // Truncate long AI responses for readability
                String display = text.length() > 300 
                    ? text.substring(0, 300) + "... (" + text.length() + " chars)"
                    : text;
                // Replace newlines with indented newlines for display
                display = display.replace("\n", "\n        ");
                System.out.println(ANSI_DIM + "  " + role + ": " + display + ANSI_RESET);
            }
        }

        System.out.println(ANSI_CYAN + "\n──────────────────────────────────────────" + ANSI_RESET);
        System.out.println(ANSI_DIM + "  Use /history all for full history, /history new to start fresh." + ANSI_RESET);
    }

    private void startNewSession(MkProContext context) throws Exception {
        // Generate a new session ID based on timestamp
        String newId = "session-" + System.currentTimeMillis();
        SessionKey sessionKey = new SessionKey("mkpro", "Coordinator", newId);
        Session newSession = context.getSessionService().createSession(sessionKey, new java.util.HashMap<>()).blockingGet();
        context.setCurrentSession(newSession);
        System.out.println(ANSI_GREEN + "✓ New session started: " + newId + ANSI_RESET);
        System.out.println(ANSI_DIM + "  Previous session preserved. Restart app to resume it (uses 'default-session')." + ANSI_RESET);
    }

    private String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    @Override
    public String getName() {
        return "history";
    }

    @Override
    public String getDescription() {
        return "Show chat history. Usage: /history [N|all|new]";
    }
}
