package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.config.ConfigService;
import com.mkpro.utils.ConsoleUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.mkpro.MkPro.ANSI_BLUE;
import static com.mkpro.MkPro.ANSI_RESET;
import static com.mkpro.MkPro.ANSI_BRIGHT_GREEN;

public class TeamCommand implements Command {
    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        ConfigService configService = new ConfigService();
        String currentTeam = context.getCurrentTeam() != null ? context.getCurrentTeam().get() : "None";

        if (args.length == 0) {
            System.out.println(ANSI_BLUE + "Current team: " + ANSI_BRIGHT_GREEN + currentTeam + ANSI_RESET);
            
            List<String> teamOptions = getAvailableTeams(context);
            if (teamOptions.isEmpty()) {
                System.out.println("No teams found in " + context.getTeamsDir());
                return;
            }

            String selectedTeam = ConsoleUtils.selectOption(context, "Select a team to switch to:", teamOptions);
            if (selectedTeam != null) {
                applyTeamSwitch(selectedTeam, context, configService);
            }
            return;
        }

        String subCommand = args[0];

        if (subCommand.equalsIgnoreCase("list")) {
            System.out.println(ANSI_BLUE + "Available teams:" + ANSI_RESET);
            try (Stream<Path> stream = Files.list(context.getTeamsDir())) {
                stream.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                      .map(p -> p.getFileName().toString())
                      .forEach(name -> {
                          String teamId = name.replace(".yaml", "").replace(".yml", "");
                          String marker = (name.equals(currentTeam) || teamId.equals(currentTeam)) ? " (*)" : "";
                          System.out.println(" - " + name + marker);
                      });
            }
        } else {
            applyTeamSwitch(subCommand, context, configService);
        }
    }

    private List<String> getAvailableTeams(MkProContext context) throws IOException {
        List<String> teams = new ArrayList<>();
        try (Stream<Path> stream = Files.list(context.getTeamsDir())) {
            stream.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                  .map(p -> p.getFileName().toString().replace(".yaml", "").replace(".yml", ""))
                  .forEach(teams::add);
        }
        return teams;
    }

    private void applyTeamSwitch(String teamName, MkProContext context, ConfigService configService) {
        Path teamFile = context.getTeamsDir().resolve(teamName);
        
        // Try adding extensions if not provided
        if (!Files.exists(teamFile)) {
            if (Files.exists(context.getTeamsDir().resolve(teamName + ".yaml"))) {
                teamFile = context.getTeamsDir().resolve(teamName + ".yaml");
            } else if (Files.exists(context.getTeamsDir().resolve(teamName + ".yml"))) {
                teamFile = context.getTeamsDir().resolve(teamName + ".yml");
            }
        }

        if (Files.exists(teamFile)) {
            context.getCurrentTeam().set(teamName);
            configService.saveSetting(ConfigService.PROP_TEAM, teamName); // Persist selection
            context.getAgentManager().reloadAgents(teamFile);
            
            // Also update the runner to use the new definitions
            context.setAgentConfigs(context.getCentralMemory().getAllAgentConfigs());
            context.setRunner(context.getAgentManager().createRunner(context.getAgentConfigs(), ""));
            
            System.out.println(ANSI_BRIGHT_GREEN + "Switched to team: " + teamName + ANSI_RESET);
        } else {
            System.err.println("Team file not found: " + teamFile);
            System.out.println("Usage: team [list | <team_name>]");
        }
    }

    @Override
    public String getName() {
        return "team";
    }

    @Override
    public String getDescription() {
        return "List or switch the current team.";
    }
}
