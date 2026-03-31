package org.dean.codex.protocol.history;

import org.dean.codex.protocol.conversation.TurnId;

import java.time.Instant;

public record HistoryRuntimeErrorItem(TurnId turnId,
                                      String message,
                                      Instant createdAt) implements ThreadHistoryItem {

    public HistoryRuntimeErrorItem(String message, Instant createdAt) {
        this(new TurnId(""), message, createdAt);
    }

    public HistoryRuntimeErrorItem {
        turnId = turnId == null ? new TurnId("") : turnId;
        message = message == null ? "" : message;
    }
}
