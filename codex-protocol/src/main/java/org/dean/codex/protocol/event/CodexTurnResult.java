package org.dean.codex.protocol.event;

import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.item.TurnItem;

import java.util.List;

public record CodexTurnResult(ThreadId threadId,
                              TurnId turnId,
                              TurnStatus status,
                              List<TurnItem> items,
                              String finalAnswer) {

    public CodexTurnResult {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
