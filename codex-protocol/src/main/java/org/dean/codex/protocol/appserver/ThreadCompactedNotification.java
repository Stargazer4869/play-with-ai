package org.dean.codex.protocol.appserver;

import org.dean.codex.protocol.context.ThreadMemory;
import org.dean.codex.protocol.conversation.ThreadId;

public record ThreadCompactedNotification(ThreadId threadId,
                                          ThreadMemory threadMemory) implements AppServerNotification {

    @Override
    public String method() {
        return "thread/compacted";
    }
}
