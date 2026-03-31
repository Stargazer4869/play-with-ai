package org.dean.codex.protocol.history;

import org.dean.codex.protocol.conversation.TurnId;

import java.time.Instant;

public record HistoryToolResultItem(TurnId turnId,
                                    String toolName,
                                    String summary,
                                    Instant createdAt) implements ThreadHistoryItem {

    public HistoryToolResultItem(String toolName, String summary, Instant createdAt) {
        this(new TurnId(""), toolName, summary, createdAt);
    }

    public HistoryToolResultItem {
        turnId = turnId == null ? new TurnId("") : turnId;
        toolName = toolName == null ? "" : toolName;
        summary = summary == null ? "" : summary;
    }
}
