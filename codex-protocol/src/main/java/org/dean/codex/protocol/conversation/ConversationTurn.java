package org.dean.codex.protocol.conversation;

import org.dean.codex.protocol.event.TurnEvent;

import java.time.Instant;
import java.util.List;

public record ConversationTurn(TurnId turnId,
                               ThreadId threadId,
                               String userInput,
                               String finalAnswer,
                               TurnStatus status,
                               Instant startedAt,
                               Instant completedAt,
                               List<TurnEvent> events) {
}
