package org.dean.codex.protocol.appserver;

import org.dean.codex.protocol.context.ReconstructedThreadContext;
import org.dean.codex.protocol.context.ThreadMemory;
import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.ThreadSummary;

import java.util.List;

public record ThreadReadResponse(ThreadSummary thread,
                                 List<ConversationTurn> turns,
                                 ThreadMemory threadMemory,
                                 ReconstructedThreadContext reconstructedContext) {

    public ThreadReadResponse {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }
}
