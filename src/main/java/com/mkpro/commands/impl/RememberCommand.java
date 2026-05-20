package com.mkpro.commands.impl;

import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.mkpro.commands.Command;
import com.mkpro.core.MkProContext;
import com.mkpro.MkPro;

import java.io.FileWriter;
import java.io.IOException;

/**
 * RememberCommand summarizes the current session and saves it to central memory.
 */
public class RememberCommand implements Command {

    @Override
    public void execute(String[] args, MkProContext context) throws Exception {
        if (context.getRunner() == null || context.getCurrentSession() == null) {
            System.out.println(MkPro.ANSI_YELLOW + "No runner or session initialized." + MkPro.ANSI_RESET);
            return;
        }

        String prompt = "Please summarize our conversation so far and extract key facts, decisions, and context that should be remembered for the next session. Be concise.";
        Content message = Content.fromParts(new Part[]{Part.fromText(prompt)});
        StringBuilder sb = new StringBuilder();

        System.out.println(MkPro.ANSI_CYAN + "Generating session summary..." + MkPro.ANSI_RESET);

        context.getRunner().runAsync(context.getCurrentSession().sessionKey(), message)
            .blockingSubscribe(event -> {
                event.content().ifPresent(content -> {
                    content.parts().ifPresent(parts -> {
                        for (Part part : parts) {
                            part.text().ifPresent(sb::append);
                        }
                    });
                });
            }, error -> {
                System.err.println("\n[Error] Failed to generate summary: " + error.getMessage());
            });

        String summary = sb.toString().trim();
        if (summary.isEmpty()) {
            System.out.println(MkPro.ANSI_RED + "Failed to generate a summary." + MkPro.ANSI_RESET);
            return;
        }

        // Save to file
        try (FileWriter writer = new FileWriter("session_summary.txt")) {
            writer.write(summary);
        } catch (IOException e) {
            System.err.println("Failed to write to session_summary.txt: " + e.getMessage());
        }

        // Save to CentralMemory
        String projectPath = System.getProperty("user.dir");
        context.getCentralMemory().saveMemory(projectPath, summary);

        System.out.println(MkPro.ANSI_GREEN + "Session remembered successfully!" + MkPro.ANSI_RESET);
        System.out.println(MkPro.ANSI_CYAN + "Summary saved to session_summary.txt and CentralMemory." + MkPro.ANSI_RESET);
    }

    @Override
    public String getName() {
        return "remember";
    }

    @Override
    public String getDescription() {
        return "Summarizes the current session and saves it to central memory.";
    }
}
