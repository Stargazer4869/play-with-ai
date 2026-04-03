package org.dean.codex.cli.config;

public final class CliConfigOverridesMapper {

    private CliConfigOverridesMapper() {
    }

    public static CliConfigOverrides fromRawValues(String model,
                                                   String cd,
                                                   String sandbox,
                                                   String approvalMode) {
        return new CliConfigOverrides(
                normalize(model),
                normalize(cd),
                CliSandboxMode.fromCliValue(sandbox),
                CliApprovalMode.fromCliValue(approvalMode));
    }

    public static CliConfigOverrides merge(CliConfigOverrides base, CliConfigOverrides overrides) {
        CliConfigOverrides left = base == null ? empty() : base;
        CliConfigOverrides right = overrides == null ? empty() : overrides;
        return new CliConfigOverrides(
                right.hasModel() ? right.model() : left.model(),
                right.hasCd() ? right.cd() : left.cd(),
                right.hasSandbox() ? right.sandbox() : left.sandbox(),
                right.hasApprovalMode() ? right.approvalMode() : left.approvalMode());
    }

    public static CliConfigOverrides empty() {
        return new CliConfigOverrides(null, null, null, null);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
