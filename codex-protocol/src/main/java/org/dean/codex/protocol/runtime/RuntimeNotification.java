package org.dean.codex.protocol.runtime;

import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.ThreadSummary;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.item.TurnItem;

import java.time.Instant;

public record RuntimeNotification(RuntimeNotificationType type,
                                  ThreadId threadId,
                                  TurnId turnId,
                                  ThreadSummary thread,
                                  RuntimeTurn turn,
                                  TurnItem item,
                                  TurnStatus status,
                                  String finalAnswer,
                                  Instant createdAt) {
}
