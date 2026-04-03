package org.dean.codex.cli.config;

public record CliConfigOverrides(String model,
                                 String cd,
                                 CliSandboxMode sandbox,
                                 CliApprovalMode approvalMode) {

    public CliConfigOverrides {
        model = normalize(model);
        cd = normalize(cd);
    }

    public boolean isEmpty() {
        return model == null
                && cd == null
                && sandbox == null
                && approvalMode == null;
    }

    public boolean hasModel() {
        return model != null;
    }

    public boolean hasCd() {
        return cd != null;
    }

    public boolean hasSandbox() {
        return sandbox != null;
    }

    public boolean hasApprovalMode() {
        return approvalMode != null;
    }

    public CliConfigOverrides withModel(String newModel) {
        return new CliConfigOverrides(newModel, cd, sandbox, approvalMode);
    }

    public CliConfigOverrides withCd(String newCd) {
        return new CliConfigOverrides(model, newCd, sandbox, approvalMode);
    }

    public CliConfigOverrides withSandbox(CliSandboxMode newSandbox) {
        return new CliConfigOverrides(model, cd, newSandbox, approvalMode);
    }

    public CliConfigOverrides withApprovalMode(CliApprovalMode newApprovalMode) {
        return new CliConfigOverrides(model, cd, sandbox, newApprovalMode);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
