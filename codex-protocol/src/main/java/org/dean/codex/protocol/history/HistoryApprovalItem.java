package org.dean.codex.protocol.history;

import org.dean.codex.protocol.item.ApprovalState;
import org.dean.codex.protocol.conversation.TurnId;

import java.time.Instant;

public record HistoryApprovalItem(TurnId turnId,
                                  ApprovalState state,
                                  String approvalId,
                                  String command,
                                  String detail,
                                  Instant createdAt) implements ThreadHistoryItem {

    public HistoryApprovalItem(ApprovalState state,
                               String approvalId,
                               String command,
                               String detail,
                               Instant createdAt) {
        this(new TurnId(""), state, approvalId, command, detail, createdAt);
    }

    public HistoryApprovalItem {
        turnId = turnId == null ? new TurnId("") : turnId;
        approvalId = approvalId == null ? "" : approvalId;
        command = command == null ? "" : command;
        detail = detail == null ? "" : detail;
    }
}
