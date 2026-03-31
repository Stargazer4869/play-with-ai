package org.dean.codex.protocol.appserver;

import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;

import java.time.Instant;
import java.util.List;

public record ThreadCompaction(String compactionId,
                               ThreadId threadId,
                               List<TurnId> sourceTurnIds,
                               int compactedTurnCount,
                               String summary,
                               Instant startedAt,
                               Instant completedAt) {

    public ThreadCompaction {
        compactionId = compactionId == null ? "" : compactionId;
        sourceTurnIds = sourceTurnIds == null ? List.of() : List.copyOf(sourceTurnIds);
        summary = summary == null ? "" : summary;
    }

    public boolean completed() {
        return completedAt != null;
    }
}
