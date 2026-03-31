package org.dean.codex.protocol.item;

import org.dean.codex.protocol.conversation.ItemId;

import java.time.Instant;

public record AgentMessageItem(ItemId itemId,
                               String text,
                               Instant createdAt) implements TurnItem {
}
