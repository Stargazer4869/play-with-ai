package org.dean.codex.protocol.tool;

public record ShellCommandResult(boolean success,
                                 String command,
                                 int exitCode,
                                 String stdout,
                                 String stderr,
                                 boolean timedOut,
                                 String workingDirectory,
                                 boolean executed,
                                 CommandApprovalDecision approvalDecision,
                                 String approvalReason,
                                 String error) {
}
