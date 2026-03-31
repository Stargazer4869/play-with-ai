package org.dean.codex.runtime.springai.history;

import org.dean.codex.protocol.conversation.MessageRole;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.history.HistoryApprovalItem;
import org.dean.codex.protocol.history.HistoryMessageItem;
import org.dean.codex.protocol.history.HistoryPlanItem;
import org.dean.codex.protocol.history.HistoryRuntimeErrorItem;
import org.dean.codex.protocol.history.HistorySkillUseItem;
import org.dean.codex.protocol.history.HistoryToolCallItem;
import org.dean.codex.protocol.history.HistoryToolResultItem;
import org.dean.codex.protocol.history.ThreadHistoryItem;
import org.dean.codex.protocol.item.AgentMessageItem;
import org.dean.codex.protocol.item.ApprovalItem;
import org.dean.codex.protocol.item.PlanItem;
import org.dean.codex.protocol.item.RuntimeErrorItem;
import org.dean.codex.protocol.item.SkillUseItem;
import org.dean.codex.protocol.item.ToolCallItem;
import org.dean.codex.protocol.item.ToolResultItem;
import org.dean.codex.protocol.item.TurnItem;
import org.dean.codex.protocol.item.UserMessageItem;

import java.util.List;

public final class ThreadHistoryMapper {

    private ThreadHistoryMapper() {
    }

    public static List<ThreadHistoryItem> map(TurnItem item) {
        return map(new TurnId(""), item);
    }

    public static List<ThreadHistoryItem> map(TurnId turnId, TurnItem item) {
        if (item instanceof UserMessageItem userMessageItem) {
            return List.of(new HistoryMessageItem(turnId, MessageRole.USER, userMessageItem.text(), userMessageItem.createdAt()));
        }
        if (item instanceof AgentMessageItem agentMessageItem) {
            return List.of(new HistoryMessageItem(turnId, MessageRole.ASSISTANT, agentMessageItem.text(), agentMessageItem.createdAt()));
        }
        if (item instanceof PlanItem planItem) {
            return List.of(new HistoryPlanItem(turnId, planItem.plan(), planItem.createdAt()));
        }
        if (item instanceof SkillUseItem skillUseItem) {
            return List.of(new HistorySkillUseItem(turnId, skillUseItem.skills(), skillUseItem.createdAt()));
        }
        if (item instanceof ToolCallItem toolCallItem) {
            return List.of(new HistoryToolCallItem(turnId, toolCallItem.toolName(), toolCallItem.target(), toolCallItem.createdAt()));
        }
        if (item instanceof ToolResultItem toolResultItem) {
            return List.of(new HistoryToolResultItem(turnId, toolResultItem.toolName(), toolResultItem.summary(), toolResultItem.createdAt()));
        }
        if (item instanceof ApprovalItem approvalItem) {
            return List.of(new HistoryApprovalItem(
                    turnId,
                    approvalItem.state(),
                    approvalItem.approvalId(),
                    approvalItem.command(),
                    approvalItem.detail(),
                    approvalItem.createdAt()));
        }
        if (item instanceof RuntimeErrorItem runtimeErrorItem) {
            return List.of(new HistoryRuntimeErrorItem(turnId, runtimeErrorItem.message(), runtimeErrorItem.createdAt()));
        }
        return List.of();
    }

    public static List<ThreadHistoryItem> map(List<? extends TurnItem> items) {
        return map(new TurnId(""), items);
    }

    public static List<ThreadHistoryItem> map(TurnId turnId, List<? extends TurnItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<ThreadHistoryItem> historyItems = new java.util.ArrayList<>();
        for (TurnItem item : items) {
            historyItems.addAll(map(turnId, item));
        }
        return List.copyOf(historyItems);
    }
}
