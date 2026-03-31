package org.dean.codex.protocol.history;

import org.dean.codex.protocol.conversation.TurnId;

import java.time.Instant;

public record HistoryCompactionSummaryItem(TurnId anchorTurnId,
                                           String summaryText,
                                           Instant createdAt) implements ThreadHistoryItem {

    public HistoryCompactionSummaryItem {
        anchorTurnId = anchorTurnId == null ? new TurnId("") : anchorTurnId;
        summaryText = summaryText == null ? "" : summaryText;
    }
}
