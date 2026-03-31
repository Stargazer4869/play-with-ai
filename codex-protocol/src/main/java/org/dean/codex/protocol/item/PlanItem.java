package org.dean.codex.protocol.item;

import org.dean.codex.protocol.conversation.ItemId;
import org.dean.codex.protocol.planning.EditPlan;

import java.time.Instant;

public record PlanItem(ItemId itemId,
                       EditPlan plan,
                       Instant createdAt) implements TurnItem {
}
