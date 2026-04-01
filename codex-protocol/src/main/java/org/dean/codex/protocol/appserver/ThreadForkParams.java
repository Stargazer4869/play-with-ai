package org.dean.codex.protocol.appserver;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.ThreadSource;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ThreadForkParams(ThreadId threadId,
                               String title,
                               Boolean ephemeral,
                               String cwd,
                               String modelProvider,
                               String model,
                               ThreadSource source,
                               String agentNickname,
                               String agentRole,
                               String agentPath) {

    public ThreadForkParams(ThreadId threadId) {
        this(threadId, null, null, null, null, null, null, null, null, null);
    }
}
