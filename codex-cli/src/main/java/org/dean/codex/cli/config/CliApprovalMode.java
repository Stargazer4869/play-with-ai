package org.dean.codex.cli.config;

import java.util.Locale;

public enum CliApprovalMode {
    REVIEW_SENSITIVE("review-sensitive"),
    AUTO("auto"),
    FULL_AUTO("full-auto");

    private final String cliValue;

    CliApprovalMode(String cliValue) {
        this.cliValue = cliValue;
    }

    public String cliValue() {
        return cliValue;
    }

    public static CliApprovalMode fromCliValue(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        for (CliApprovalMode mode : values()) {
            if (mode.matches(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unsupported approval mode: " + value);
    }

    private boolean matches(String candidate) {
        return cliValue.equals(candidate) || name().toLowerCase(Locale.ROOT).replace('_', '-').equals(candidate);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return normalized.isEmpty() ? null : normalized;
    }
}
