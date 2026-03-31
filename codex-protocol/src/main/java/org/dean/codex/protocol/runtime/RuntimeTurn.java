package org.dean.codex.protocol.runtime;

import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;

import java.time.Instant;

public record RuntimeTurn(ThreadId threadId,
                          TurnId turnId,
                          TurnStatus status,
                          Instant startedAt,
                          Instant completedAt) {
}
