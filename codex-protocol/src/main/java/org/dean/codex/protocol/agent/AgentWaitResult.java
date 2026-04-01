package org.dean.codex.protocol.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentWaitResult(ThreadId threadId,
                              TurnId turnId,
                              AgentStatus previousStatus,
                              AgentStatus status,
                              boolean timedOut,
                              String message,
                              String finalAnswer,
                              Instant completedAt) {

    public AgentWaitResult {
        message = message == null ? "" : message.trim();
        finalAnswer = finalAnswer == null ? "" : finalAnswer.trim();
    }
}
