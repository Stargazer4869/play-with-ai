package org.dean.codex.protocol.history;

import org.dean.codex.protocol.conversation.TurnId;

import java.time.Instant;

public record HistoryToolCallItem(TurnId turnId,
                                  String toolName,
                                  String target,
                                  Instant createdAt) implements ThreadHistoryItem {

    public HistoryToolCallItem(String toolName, String target, Instant createdAt) {
        this(new TurnId(""), toolName, target, createdAt);
    }

    public HistoryToolCallItem {
        turnId = turnId == null ? new TurnId("") : turnId;
        toolName = toolName == null ? "" : toolName;
        target = target == null ? "" : target;
    }
}
