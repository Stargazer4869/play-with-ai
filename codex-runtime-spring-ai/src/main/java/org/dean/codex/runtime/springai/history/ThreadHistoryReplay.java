package org.dean.codex.runtime.springai.history;

import org.dean.codex.protocol.history.CompactedHistoryItem;
import org.dean.codex.protocol.history.ThreadHistoryItem;

import java.util.ArrayList;
import java.util.List;

public final class ThreadHistoryReplay {

    private ThreadHistoryReplay() {
    }

    public static List<ThreadHistoryItem> replayVisibleHistory(List<ThreadHistoryItem> historyItems) {
        if (historyItems == null || historyItems.isEmpty()) {
            return List.of();
        }
        List<ThreadHistoryItem> visibleHistory = new ArrayList<>();
        for (ThreadHistoryItem item : historyItems) {
            if (item instanceof CompactedHistoryItem compactedHistoryItem) {
                visibleHistory = new ArrayList<>(replayVisibleHistory(compactedHistoryItem.replacementHistory()));
                continue;
            }
            visibleHistory.add(item);
        }
        return List.copyOf(visibleHistory);
    }
}
