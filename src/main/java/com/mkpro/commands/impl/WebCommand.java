package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;

/**
 * /web command — toggle web UI access on/off during a session.
 *
 * Usage:
 *   /web         - Show current status (on/off, port, connected clients)
 *   /web on      - Start web server (default port 8080)
 *   /web on 9090 - Start web server on custom port
 *   /web off     - Stop web server, disconnect all clients
 */
public class WebCommand implements Command {

    @Override
    public String getName() {
        return "web";
    }

    @Override
    public String getDescription() {
        return "Toggle web UI access. Usage: /web [on [port]|off]";
    }

    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        if (args.length == 0) {
            showStatus(context);
            return;
        }

        String subcommand = args[0].toLowerCase();

        switch (subcommand) {
            case "on" -> startWeb(args, context);
            case "off" -> stopWeb(context);
            default -> showStatus(context);
        }
    }

    private void showStatus(MkProContext context) {
        com.mkpro.web.WebChatServer server = context.getWebChatServer();
        if (server == null) {
            System.out.println("\u001b[33m[Web] Web UI is OFF. Use /web on [port] to start.\u001b[0m");
        } else {
            System.out.println("\u001b[32m[Web] Web UI is ON\u001b[0m");
            System.out.println("  Chat:      http://localhost:" + server.getHttpPort());
            System.out.println("  Knowledge: http://localhost:" + server.getHttpPort() + "/knowledge");
            System.out.println("  DB:        http://localhost:" + server.getHttpPort() + "/db");
            System.out.println("  Clients:   " + server.getClientCount() + " connected");
        }
    }

    private void startWeb(String[] args, MkProContext context) throws Exception {
        if (context.getWebChatServer() != null) {
            System.out.println("\u001b[33m[Web] Already running on port " + context.getWebChatServer().getHttpPort() + ". Use /web off first.\u001b[0m");
            return;
        }

        int port = 8080;
        if (args.length > 1) {
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.out.println("\u001b[31mInvalid port: " + args[1] + "\u001b[0m");
                return;
            }
        }

        try {
            com.mkpro.web.WebChatServer webServer = new com.mkpro.web.WebChatServer(port);
            webServer.setCentralMemory(context.getCentralMemory());
            webServer.setContext(context);
            if (context.getKnowledgeStore() != null && context.getTopicIndex() != null) {
                webServer.setKnowledgeComponents(context.getKnowledgeStore(), context.getTopicIndex());
            }
            webServer.start();
            context.setWebChatServer(webServer);

            // Wire input handler (process web messages via runner)
            final com.mkpro.core.MkProContext ctx = context;
            webServer.setInputHandler(text -> {
                new Thread(() -> com.mkpro.MkPro.processWebInputPublic(ctx, text), "web-input").start();
            });

            // Wire command registry
            if (com.mkpro.MkPro.getWebRegistry() != null) {
                webServer.setCommandRegistry(com.mkpro.MkPro.getWebRegistry());
            }

            // Register WebSocket sink on event bus
            if (context.getEventBus() != null) {
                context.getEventBus().register(new com.mkpro.events.WebSocketSink(webServer));
            }

            System.out.println("\u001b[32m[Web] Started on port " + port + "\u001b[0m");
            System.out.println("  → http://localhost:" + port);

            // Remember preference for next session
            context.getCentralMemory().saveMemory("pref:web", String.valueOf(port));

        } catch (Exception e) {
            System.out.println("\u001b[31m[Web] Failed to start: " + e.getMessage() + "\u001b[0m");
        }
    }

    private void stopWeb(MkProContext context) {
        com.mkpro.web.WebChatServer server = context.getWebChatServer();
        if (server == null) {
            System.out.println("\u001b[33m[Web] Not running.\u001b[0m");
            return;
        }

        server.stop();
        context.setWebChatServer(null);
        System.out.println("\u001b[32m[Web] Stopped. All clients disconnected.\u001b[0m");

        // Remember preference for next session
        context.getCentralMemory().saveMemory("pref:web", "off");
    }
}
