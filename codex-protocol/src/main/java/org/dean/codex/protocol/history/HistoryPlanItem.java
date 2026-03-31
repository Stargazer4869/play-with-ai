package org.dean.codex.protocol.history;

import org.dean.codex.protocol.planning.EditPlan;
import org.dean.codex.protocol.conversation.TurnId;

import java.time.Instant;

public record HistoryPlanItem(TurnId turnId,
                              EditPlan plan,
                              Instant createdAt) implements ThreadHistoryItem {

    public HistoryPlanItem(EditPlan plan, Instant createdAt) {
        this(new TurnId(""), plan, createdAt);
    }

    public HistoryPlanItem {
        turnId = turnId == null ? new TurnId("") : turnId;
    }
}
