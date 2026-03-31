package org.dean.codex.core.history;

import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.history.ThreadHistoryItem;

import java.util.List;

public interface ThreadHistoryStore {

    void append(ThreadId threadId, List<ThreadHistoryItem> items);

    List<ThreadHistoryItem> read(ThreadId threadId);

    void replace(ThreadId threadId, List<ThreadHistoryItem> items);
}
