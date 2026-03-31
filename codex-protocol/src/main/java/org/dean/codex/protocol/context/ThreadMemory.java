package org.dean.codex.protocol.context;

import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;

import java.time.Instant;
import java.util.List;

public record ThreadMemory(String memoryId,
                           ThreadId threadId,
                           String summary,
                           List<TurnId> sourceTurnIds,
                           int compactedTurnCount,
                           Instant createdAt) {

    public ThreadMemory {
        sourceTurnIds = sourceTurnIds == null ? List.of() : List.copyOf(sourceTurnIds);
    }
}
