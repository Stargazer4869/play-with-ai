package org.dean.codex.protocol.conversation;

import org.dean.codex.protocol.event.TurnEvent;
import org.dean.codex.protocol.item.TurnItem;

import java.time.Instant;
import java.util.List;

public record ConversationTurn(TurnId turnId,
                               ThreadId threadId,
                               String userInput,
                               String finalAnswer,
                               TurnStatus status,
                               Instant startedAt,
                               Instant completedAt,
                               List<TurnEvent> events,
                               List<TurnItem> items) {

    public ConversationTurn {
        events = events == null ? List.of() : List.copyOf(events);
        items = items == null ? List.of() : List.copyOf(items);
    }
}
