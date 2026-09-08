package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.graph.viz.GraphVisualizerApp;
import com.mkpro.utils.PathUtils;

public class VisualizeCommand implements Command {
    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        System.out.println("Launching Graph Visualizer...");
        String dbPath = PathUtils.getBaseDocumentsPath().resolve("memory_graph.db").toString();
        String memoryKey = "global_memory";
        
        new Thread(() -> {
            GraphVisualizerApp.main(new String[]{dbPath, memoryKey});
        }).start();
    }

    @Override
    public String getName() {
        return "visualize";
    }

    @Override
    public String getDescription() {
        return "Launch the Graph Memory Visualizer window.";
    }
}
