package org.dean.codex.runtime.springai.context;

import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.history.ThreadHistoryItem;

import java.util.List;

public interface ThreadCompactionSummarizer {

    String summarize(ThreadId threadId, List<ThreadHistoryItem> compactedHistory, List<ThreadHistoryItem> retainedHistory);
}
