package org.dean.codex.protocol.item;

import org.dean.codex.protocol.conversation.ItemId;

import java.time.Instant;

public record ApprovalItem(ItemId itemId,
                           ApprovalState state,
                           String approvalId,
                           String command,
                           String detail,
                           Instant createdAt) implements TurnItem {
}
