package org.dean.codex.protocol.event;

import org.dean.codex.protocol.conversation.ItemId;

import java.time.Instant;

public record TurnEvent(ItemId itemId,
                        String type,
                        String detail,
                        Instant createdAt) {
}
