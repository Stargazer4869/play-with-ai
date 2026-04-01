package org.dean.codex.protocol.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.dean.codex.protocol.conversation.ThreadId;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentMessage(ThreadId senderThreadId,
                           ThreadId receiverThreadId,
                           String content,
                           Instant createdAt) {

    public AgentMessage {
        content = content == null ? "" : content.trim();
    }
}
