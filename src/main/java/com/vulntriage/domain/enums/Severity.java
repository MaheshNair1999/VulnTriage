package com.vulntriage.domain.enums;

/**
 * Severity levels as reported by Semgrep.
 * Ordered from lowest to highest for comparison purposes.
 */
public enum Severity {
    INFO,
    WARNING,
    ERROR;

    /**
     * Parse a raw string from scanner output, case-insensitively.
     * Defaults to INFO if the string is unrecognised.
     */
    public static Severity fromString(String raw) {
        if (raw == null) return INFO;
        return switch (raw.trim().toUpperCase()) {
            case "ERROR"   -> ERROR;
            case "WARNING" -> WARNING;
            default        -> INFO;
        };
    }
}
