package org.dean.codex.protocol.item;

import org.dean.codex.protocol.conversation.ItemId;

import java.time.Instant;

public record ToolResultItem(ItemId itemId,
                             String toolName,
                             String summary,
                             Instant createdAt) implements TurnItem {
}
