package org.dean.codex.protocol.context;

import org.dean.codex.protocol.conversation.TurnId;

import java.time.Instant;

public record ReconstructedTurnActivity(TurnId turnId,
                                        String sourceType,
                                        String detail,
                                        Instant createdAt) {
}
