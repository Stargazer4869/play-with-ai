package org.dean.codex.protocol.appserver;

import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.ThreadSummary;

import java.util.List;

public record ThreadRollbackResponse(ThreadSummary thread, List<ConversationTurn> turns) {

    public ThreadRollbackResponse {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }
}
