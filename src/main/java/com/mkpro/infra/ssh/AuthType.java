package com.mkpro.infra.ssh;

/**
 * Supported authentication types for remote SSH sessions.
 */
public enum AuthType {
    PASSWORD,
    PRIVATE_KEY,
    KEY_FILE,
    KEY_CONTENT,
    NONE;

    /**
     * Parses an AuthType from a string name, case-insensitively.
     * Defaults to PASSWORD if null or unknown.
     */
    public static AuthType fromString(String value) {
        if (value == null || value.isBlank()) {
            return PASSWORD;
        }
        String norm = value.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        return switch (norm) {
            case "PASSWORD", "PASS" -> PASSWORD;
            case "PRIVATE_KEY", "KEY_FILE", "KEY", "PEM", "RSA", "ED25519" -> KEY_FILE;
            case "KEY_CONTENT", "PRIVATE_KEY_CONTENT", "INLINE_KEY" -> KEY_CONTENT;
            case "NONE", "NO_AUTH" -> NONE;
            default -> {
                try {
                    yield AuthType.valueOf(norm);
                } catch (IllegalArgumentException e) {
                    yield PASSWORD;
                }
            }
        };
    }
}
