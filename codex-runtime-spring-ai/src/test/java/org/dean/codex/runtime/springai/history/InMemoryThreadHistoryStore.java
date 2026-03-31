package org.dean.codex.runtime.springai.history;

import org.dean.codex.core.history.ThreadHistoryStore;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.history.ThreadHistoryItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryThreadHistoryStore implements ThreadHistoryStore {

    private final Map<ThreadId, List<ThreadHistoryItem>> historyByThread = new LinkedHashMap<>();

    @Override
    public synchronized void append(ThreadId threadId, List<ThreadHistoryItem> items) {
        historyByThread.computeIfAbsent(threadId, ignored -> new ArrayList<>()).addAll(items == null ? List.of() : items);
    }

    @Override
    public synchronized List<ThreadHistoryItem> read(ThreadId threadId) {
        return List.copyOf(historyByThread.getOrDefault(threadId, List.of()));
    }

    @Override
    public synchronized void replace(ThreadId threadId, List<ThreadHistoryItem> items) {
        historyByThread.put(threadId, new ArrayList<>(items == null ? List.of() : items));
    }
}
