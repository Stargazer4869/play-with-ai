package org.dean.codex.protocol.conversation;

import java.time.Instant;

public record ThreadSummary(ThreadId threadId,
                            String title,
                            Instant createdAt,
                            Instant updatedAt,
                            int turnCount) {
}
