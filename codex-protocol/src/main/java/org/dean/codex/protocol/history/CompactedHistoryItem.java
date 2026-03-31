package org.dean.codex.protocol.history;

import java.time.Instant;
import java.util.List;

public record CompactedHistoryItem(String summaryText,
                                   List<ThreadHistoryItem> replacementHistory,
                                   Instant createdAt,
                                   CompactionStrategy strategy) implements ThreadHistoryItem {

    public CompactedHistoryItem {
        summaryText = summaryText == null ? "" : summaryText;
        replacementHistory = replacementHistory == null ? List.of() : List.copyOf(replacementHistory);
    }
}
