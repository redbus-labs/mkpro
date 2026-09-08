package com.mkpro.ui;

/**
 * Central ANSI color constants for terminal output.
 * All terminal-colored output should reference these constants.
 */
public final class AnsiColors {

    private AnsiColors() {} // Utility class

    public static final String RESET = "\u001b[0m";
    public static final String BOLD = "\u001b[1m";
    public static final String DIM = "\u001b[90m";

    // Standard colors
    public static final String RED = "\u001b[31m";
    public static final String GREEN = "\u001b[32m";
    public static final String YELLOW = "\u001b[33m";
    public static final String BLUE = "\u001b[34m";
    public static final String PURPLE = "\u001b[35m";
    public static final String CYAN = "\u001b[36m";
    public static final String WHITE = "\u001b[37m";

    // Bright variants
    public static final String BRIGHT_GREEN = "\u001b[92m";
    public static final String BRIGHT_MAGENTA = "\u001B[95m";

    // Extended (256-color)
    public static final String LIGHT_ORANGE = "\u001b[38;5;214m";
    public static final String LIGHT_PURPLE = "\u001b[38;5;177m";

    // Compound
    public static final String RED_BOLD = "\u001b[1;31m";
    public static final String RED_ON_WHITE = "\u001b[31;47m";

    // ═══ ANSI_ prefixed aliases (for backward compatibility with existing code) ═══
    public static final String ANSI_RESET = RESET;
    public static final String ANSI_BOLD = BOLD;
    public static final String ANSI_DIM = DIM;
    public static final String ANSI_RED = RED;
    public static final String ANSI_GREEN = GREEN;
    public static final String ANSI_YELLOW = YELLOW;
    public static final String ANSI_BLUE = BLUE;
    public static final String ANSI_PURPLE = PURPLE;
    public static final String ANSI_CYAN = CYAN;
    public static final String ANSI_WHITE = WHITE;
    public static final String ANSI_BRIGHT_GREEN = BRIGHT_GREEN;
    public static final String ANSI_BRIGHT_MAGENTA = BRIGHT_MAGENTA;
    public static final String ANSI_LIGHT_ORANGE = LIGHT_ORANGE;
    public static final String ANSI_LIGHT_PURPLE = LIGHT_PURPLE;
    public static final String ANSI_RED_BOLD = RED_BOLD;
    public static final String ANSI_RED_ON_WHITE = RED_ON_WHITE;
}
