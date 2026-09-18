package com.mkpro.utils;

import com.mkpro.core.MkProContext;
import org.jline.reader.LineReader;

import java.util.List;

import static com.mkpro.MkPro.ANSI_BLUE;
import static com.mkpro.MkPro.ANSI_GREEN;
import static com.mkpro.MkPro.ANSI_RESET;

public class ConsoleUtils {

    public static String selectOption(MkProContext context, String prompt, List<String> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }

        LineReader reader = context.getLineReader();
        if (reader == null) {
            System.err.println("LineReader not initialized.");
            return null;
        }

        System.out.println(ANSI_BLUE + prompt + ANSI_RESET);
        for (int i = 0; i < options.size(); i++) {
            System.out.println(ANSI_GREEN + String.format(" %d. %s", i + 1, options.get(i)) + ANSI_RESET);
        }
        System.out.println(ANSI_GREEN + String.format(" %d. Cancel", options.size() + 1) + ANSI_RESET);

        while (true) {
            String input = reader.readLine("Choice: ").trim();
            if (input.isEmpty()) continue;

            try {
                int choice = Integer.parseInt(input);
                if (choice >= 1 && choice <= options.size()) {
                    return options.get(choice - 1);
                } else if (choice == options.size() + 1) {
                    return null;
                }
            } catch (NumberFormatException e) {
                // Try to match by name
                for (String option : options) {
                    if (option.equalsIgnoreCase(input)) {
                        return option;
                    }
                }
                if (input.equalsIgnoreCase("cancel")) {
                    return null;
                }
            }
            System.out.println("Invalid choice. Please try again.");
        }
    }
}
