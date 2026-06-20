package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;

public class QuitCommand implements Command {
    @Override
    public void execute(String[] args, MkProContext context) {
        System.out.println("Goodbye.");
        System.exit(0);
    }

    @Override
    public String getName() {
        return "quit";
    }

    @Override
    public String getDescription() {
        return "Exit mkpro.";
    }
}
