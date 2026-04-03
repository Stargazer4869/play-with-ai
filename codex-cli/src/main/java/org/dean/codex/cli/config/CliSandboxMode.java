package org.dean.codex.cli.config;

import java.util.Locale;

public enum CliSandboxMode {
    WORKSPACE_WRITE("workspace-write"),
    READ_ONLY("read-only"),
    FULL_ACCESS("full-access");

    private final String cliValue;

    CliSandboxMode(String cliValue) {
        this.cliValue = cliValue;
    }

    public String cliValue() {
        return cliValue;
    }

    public static CliSandboxMode fromCliValue(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        for (CliSandboxMode mode : values()) {
            if (mode.matches(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unsupported sandbox mode: " + value);
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
