package org.dean.codex.protocol.history;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.dean.codex.protocol.conversation.TurnId;

import java.time.Instant;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = HistoryMessageItem.class, name = "historyMessage"),
        @JsonSubTypes.Type(value = HistoryToolCallItem.class, name = "historyToolCall"),
        @JsonSubTypes.Type(value = HistoryToolResultItem.class, name = "historyToolResult"),
        @JsonSubTypes.Type(value = HistoryPlanItem.class, name = "historyPlan"),
        @JsonSubTypes.Type(value = HistorySkillUseItem.class, name = "historySkillUse"),
        @JsonSubTypes.Type(value = HistoryApprovalItem.class, name = "historyApproval"),
        @JsonSubTypes.Type(value = HistoryRuntimeErrorItem.class, name = "historyRuntimeError"),
        @JsonSubTypes.Type(value = HistoryCompactionSummaryItem.class, name = "historyCompactionSummary"),
        @JsonSubTypes.Type(value = CompactedHistoryItem.class, name = "compactedHistory")
})
public sealed interface ThreadHistoryItem permits HistoryMessageItem,
        HistoryToolCallItem,
        HistoryToolResultItem,
        HistoryPlanItem,
        HistorySkillUseItem,
        HistoryApprovalItem,
        HistoryRuntimeErrorItem,
        HistoryCompactionSummaryItem,
        CompactedHistoryItem {

    Instant createdAt();

    default TurnId turnId() {
        return new TurnId("");
    }
}
