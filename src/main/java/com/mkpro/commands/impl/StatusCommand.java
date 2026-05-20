package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;

public class StatusCommand implements Command {
    @Override
    public void execute(String[] args, MkProContext context) {
        context.getTerminal().writer().println("\n--- Application Status ---");
        context.getTerminal().writer().println("Runner Type: " + (context.getCurrentRunnerType() != null ? context.getCurrentRunnerType().get() : "Unknown"));
        context.getTerminal().writer().println("Current Team: " + (context.getCurrentTeam() != null ? context.getCurrentTeam().get() : "None"));
        context.getTerminal().writer().println("Session ID: " + (context.getCurrentSession() != null ? context.getCurrentSession().id() : "None"));
        context.getTerminal().writer().println("Instance Name: " + context.getInstanceName());
        context.getTerminal().writer().println("Maker Mode: " + (context.getMakerEnabled() != null && context.getMakerEnabled().get() ? "Enabled" : "Disabled"));
        context.getTerminal().writer().println("Verbose: " + context.isVerbose());
        context.getTerminal().writer().println("--------------------------\n");
        context.getTerminal().flush();
    }

    @Override
    public String getName() {
        return "status";
    }

    @Override
    public String getDescription() {
        return "Show application status and configuration";
    }
}
