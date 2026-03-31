package org.dean.codex.protocol.item;

import org.dean.codex.protocol.conversation.ItemId;

import java.time.Instant;

public record RuntimeErrorItem(ItemId itemId,
                               String message,
                               Instant createdAt) implements TurnItem {
}
