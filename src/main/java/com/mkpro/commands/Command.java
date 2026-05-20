package com.mkpro.commands;

import com.mkpro.core.MkProContext;

public interface Command {
    void execute(String[] args, MkProContext context) throws Exception;
    String getName();
    String getDescription();
}
