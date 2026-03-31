package org.dean.codex.protocol.history;

import org.dean.codex.protocol.conversation.MessageRole;
import org.dean.codex.protocol.conversation.TurnId;

import java.time.Instant;

public record HistoryMessageItem(TurnId turnId,
                                 MessageRole role,
                                 String text,
                                 Instant createdAt) implements ThreadHistoryItem {

    public HistoryMessageItem(MessageRole role, String text, Instant createdAt) {
        this(new TurnId(""), role, text, createdAt);
    }

    public HistoryMessageItem {
        turnId = turnId == null ? new TurnId("") : turnId;
        text = text == null ? "" : text;
    }
}
