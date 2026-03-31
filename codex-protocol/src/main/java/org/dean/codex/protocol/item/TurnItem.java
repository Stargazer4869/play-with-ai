package org.dean.codex.protocol.item;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.dean.codex.protocol.conversation.ItemId;

import java.time.Instant;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = UserMessageItem.class, name = "userMessage"),
        @JsonSubTypes.Type(value = AgentMessageItem.class, name = "agentMessage"),
        @JsonSubTypes.Type(value = PlanItem.class, name = "plan"),
        @JsonSubTypes.Type(value = ToolCallItem.class, name = "toolCall"),
        @JsonSubTypes.Type(value = ToolResultItem.class, name = "toolResult"),
        @JsonSubTypes.Type(value = SkillUseItem.class, name = "skillUse"),
        @JsonSubTypes.Type(value = ApprovalItem.class, name = "approval"),
        @JsonSubTypes.Type(value = RuntimeErrorItem.class, name = "runtimeError")
})
public sealed interface TurnItem permits UserMessageItem,
        AgentMessageItem,
        PlanItem,
        ToolCallItem,
        ToolResultItem,
        SkillUseItem,
        ApprovalItem,
        RuntimeErrorItem {

    ItemId itemId();

    Instant createdAt();
}
