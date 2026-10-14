package com.mkpro.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InstructionSanitizer {

    /**
     * Regex explanation:
     * \{                - Matches opening curly brace
     * (                 - Capture Group 1: The identifier
     *   [a-zA-Z_]       - Identifier must start with letter/underscore
     *   [a-zA-Z0-9_]*   - Followed by alphanumeric or underscores
     *   (?:             - Non-capturing group for optional prefix
     *     :             - Literal colon
     *     [a-zA-Z_]     - Prefix identifier must start with letter/underscore
     *     [a-zA-Z0-9_]* - Followed by alphanumeric or underscores
     *   )?              - The prefix is optional
     * )
     * (\?)?             - Capture Group 2: Optional '?'
     * \}                - Matches closing curly brace
     */
    private static final Pattern BRACE_PATTERN = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*(?::[a-zA-Z_][a-zA-Z0-9_]*)?)(\\?)?\\}");

    public static String sanitize(String instruction) {
        if (instruction == null) return null;
        if (instruction.isEmpty()) return instruction;

        // Ensure we don't match JSON-like structures.
        // The regex above is strict enough that it won't match:
        // - {"key": "val"} (contains spaces and quotes)
        // - { "key": ... } (contains spaces)
        // - {key: "val"}   (contains quotes/spaces)
        
        Matcher matcher = BRACE_PATTERN.matcher(instruction);
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;

        while (matcher.find()) {
            sb.append(instruction, lastEnd, matcher.start());
            
            // Reconstruct the sanitized identifier: [id] or [prefix:id]
            String identifier = matcher.group(1);
            sb.append("[").append(identifier).append("]");
            
            lastEnd = matcher.end();
        }
        sb.append(instruction.substring(lastEnd));

        return sb.toString();
    }
}
