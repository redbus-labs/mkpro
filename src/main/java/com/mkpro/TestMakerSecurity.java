package com.mkpro;

public class TestMakerSecurity {
    public static void main(String[] args) {
        int passed = 0;
        int failed = 0;

        if (test("ls -la", true)) passed++; else failed++;
        if (test("rm -rf /", false)) passed++; else failed++;
        if (test("  SHUTDOWN  ", false)) passed++; else failed++;
        if (test(null, false)) passed++; else failed++;

        System.out.println("\nTests passed: " + passed);
        System.out.println("Tests failed: " + failed);

        if (failed > 0) {
            System.exit(1);
        }
    }

    private static boolean test(String command, boolean expected) {
        boolean actual = Maker.isAllowed(command);
        boolean success = (actual == expected);
        System.out.println("Testing: [" + command + "] - Expected: " + expected + ", Actual: " + actual + " - " + (success ? "PASS" : "FAIL"));
        return success;
    }
}