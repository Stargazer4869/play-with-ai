package org.dean.codex.protocol.appserver;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.dean.codex.protocol.conversation.ThreadId;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ThreadReadParams(ThreadId threadId, boolean includeTurns) {

    public ThreadReadParams(ThreadId threadId) {
        this(threadId, true);
    }
}
