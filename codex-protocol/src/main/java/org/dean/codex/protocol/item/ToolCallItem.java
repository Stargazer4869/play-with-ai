package org.dean.codex.protocol.item;

import org.dean.codex.protocol.conversation.ItemId;

import java.time.Instant;

public record ToolCallItem(ItemId itemId,
                           String toolName,
                           String target,
                           Instant createdAt) implements TurnItem {
}
