package com.mkpro.commands.impl;

import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.config.ConfigService;
import com.mkpro.utils.ConsoleUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
                stream.filter(this::isValidTeamEntry)
                      .sorted()
                      .forEach(p -> {
                          String name = p.getFileName().toString();
                          String teamId = name.replace(".yaml", "").replace(".yml", "");
                          boolean isModular = Files.isDirectory(p);
                          String display = isModular ? name + "/ (modular ADK)" : name;
                          String marker = (name.equals(currentTeam) || teamId.equals(currentTeam)) ? " (*)" : "";
                          System.out.println(" - " + display + marker);
                      });
            }
        } else {
            applyTeamSwitch(subCommand, context, configService);
        }
    }

    private boolean isValidTeamEntry(Path p) {
        if (Files.isRegularFile(p)) {
            String fileName = p.getFileName().toString();
            return fileName.endsWith(".yaml") || fileName.endsWith(".yml");
        } else if (Files.isDirectory(p)) {
            return Files.exists(p.resolve("agent.yaml")) || Files.exists(p.resolve("agent.yml"));
        }
        return false;
    }

    private List<String> getAvailableTeams(MkProContext context) throws IOException {
        Set<String> teams = new LinkedHashSet<>();
        try (Stream<Path> stream = Files.list(context.getTeamsDir())) {
            stream.filter(this::isValidTeamEntry)
                  .sorted()
                  .map(p -> p.getFileName().toString().replace(".yaml", "").replace(".yml", ""))
                  .forEach(teams::add);
        }
        return new ArrayList<>(teams);
    }

    private void applyTeamSwitch(String teamName, MkProContext context, ConfigService configService) {
        String cleanTeamName = teamName.endsWith("/") ? teamName.substring(0, teamName.length() - 1) : teamName;
        Path teamPath = context.getTeamsDir().resolve(cleanTeamName);

        // Prefer modular directory if it contains agent.yaml / agent.yml, otherwise check .yaml / .yml files
        if (Files.isDirectory(teamPath) && (Files.exists(teamPath.resolve("agent.yaml")) || Files.exists(teamPath.resolve("agent.yml")))) {
            // Use modular directory
        } else if (!Files.exists(teamPath) || Files.isDirectory(teamPath)) {
            if (Files.exists(context.getTeamsDir().resolve(cleanTeamName + ".yaml"))) {
                teamPath = context.getTeamsDir().resolve(cleanTeamName + ".yaml");
            } else if (Files.exists(context.getTeamsDir().resolve(cleanTeamName + ".yml"))) {
                teamPath = context.getTeamsDir().resolve(cleanTeamName + ".yml");
            }
        }

        if (Files.exists(teamPath)) {
            String teamId = cleanTeamName.replace(".yaml", "").replace(".yml", "");
            context.getCurrentTeam().set(teamId);
            configService.saveSetting(ConfigService.PROP_TEAM, teamId); // Persist selection
            context.getAgentManager().reloadAgents(teamPath);
            
            // Also update the runner to use the new definitions
            context.setAgentConfigs(context.getCentralMemory().getAllAgentConfigs());
            context.setRunner(context.getAgentManager().createRunner(context.getAgentConfigs(), "", context.getMaxTurns()));
            
            System.out.println(ANSI_BRIGHT_GREEN + "Switched to team: " + teamId + ANSI_RESET);
        } else {
            System.err.println("Team file or modular directory not found: " + teamPath);
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
