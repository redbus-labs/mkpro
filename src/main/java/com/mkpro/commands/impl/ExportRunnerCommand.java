package com.mkpro.commands.impl;

import com.google.adk.runner.MapDbRunner;
import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.models.RunnerType;

import java.nio.file.Paths;

public class ExportRunnerCommand implements Command {

    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        RunnerType runnerType = context.getCurrentRunnerType().get();

        if (runnerType != RunnerType.MAP_DB) {
            System.err.println("Export is only supported for MAP_DB runner.");
            return;
        }

        if (context.getRunner() instanceof MapDbRunner) {
            MapDbRunner runner = (MapDbRunner) context.getRunner();
            runner.exportToJson(Paths.get("export.json"));
            System.out.println("Successfully exported MapDB runner data to export.json");
        } else {
            System.err.println("Current runner is not an instance of MapDbRunner.");
        }
    }

    @Override
    public String getName() {
        return "export_runner";
    }

    @Override
    public String getDescription() {
        return "Export all MapDB runner data to a JSON file";
    }
}
