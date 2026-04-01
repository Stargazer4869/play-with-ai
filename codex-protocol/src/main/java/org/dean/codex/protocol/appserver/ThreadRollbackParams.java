package org.dean.codex.protocol.appserver;

import org.dean.codex.protocol.conversation.ThreadId;

public record ThreadRollbackParams(ThreadId threadId, int numTurns) {

    public ThreadRollbackParams(ThreadId threadId) {
        this(threadId, 1);
    }
}
