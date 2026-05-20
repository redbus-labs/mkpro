package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.utils.IndexingHelper;

public class IndexCommand implements Command {
    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        IndexingHelper.indexProject(context);
        System.out.println("Project indexing completed.");
    }

    @Override
    public String getName() {
        return "index";
    }

    @Override
    public String getDescription() {
        return "Trigger project indexing for better context awareness.";
    }
}
