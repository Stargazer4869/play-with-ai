package org.dean.codex.protocol.approval;

import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.tool.ShellCommandResult;

import java.time.Instant;

public record CommandApprovalRequest(ApprovalId approvalId,
                                     ThreadId threadId,
                                     TurnId turnId,
                                     String command,
                                     String workingDirectory,
                                     String reason,
                                     ApprovalStatus status,
                                     Instant createdAt,
                                     Instant updatedAt,
                                     String resolutionNote,
                                     ShellCommandResult executionResult) {
}
