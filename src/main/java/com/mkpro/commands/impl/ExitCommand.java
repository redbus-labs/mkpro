package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;

public class ExitCommand implements Command {

    @Override
    public void execute(String[] args, MkProContext context) {
        System.out.println("\u001b[33mGoodbye!\u001b[0m");
        System.exit(0);
    }

    @Override
    public String getName() {
        return "exit";
    }

    @Override
    public String getDescription() {
        return "Exit the application.";
    }
}
