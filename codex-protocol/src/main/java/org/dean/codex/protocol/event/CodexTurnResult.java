package org.dean.codex.protocol.event;

import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;

import java.util.List;

public record CodexTurnResult(ThreadId threadId,
                              TurnId turnId,
                              TurnStatus status,
                              List<TurnEvent> events,
                              String finalAnswer) {
}
