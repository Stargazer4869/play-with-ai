package org.dean.codex.protocol.tool;

public record CommandApproval(CommandApprovalDecision decision,
                              String reason) {
}
