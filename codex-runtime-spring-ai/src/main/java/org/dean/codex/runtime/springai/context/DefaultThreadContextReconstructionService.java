package org.dean.codex.runtime.springai.context;

import org.dean.codex.core.context.ContextManager;
import org.dean.codex.core.context.ThreadContextReconstructionService;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.protocol.context.ReconstructedThreadContext;
import org.dean.codex.protocol.context.ReconstructedTurnActivity;
import org.dean.codex.protocol.context.ThreadMemory;
import org.dean.codex.protocol.conversation.ConversationMessage;
import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.event.TurnEvent;
import org.dean.codex.protocol.item.AgentMessageItem;
import org.dean.codex.protocol.item.ApprovalItem;
import org.dean.codex.protocol.item.PlanItem;
import org.dean.codex.protocol.item.RuntimeErrorItem;
import org.dean.codex.protocol.item.SkillUseItem;
import org.dean.codex.protocol.item.ToolCallItem;
import org.dean.codex.protocol.item.ToolResultItem;
import org.dean.codex.protocol.item.TurnItem;
import org.dean.codex.protocol.item.UserMessageItem;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DefaultThreadContextReconstructionService implements ThreadContextReconstructionService {

    private final ConversationStore conversationStore;
    private final ContextManager contextManager;
    private final int historyWindow;

    public DefaultThreadContextReconstructionService(ConversationStore conversationStore,
                                                     ContextManager contextManager,
                                                     int historyWindow) {
        this.conversationStore = conversationStore;
        this.contextManager = contextManager;
        this.historyWindow = Math.max(1, historyWindow);
    }

    @Override
    public ReconstructedThreadContext reconstruct(ThreadId threadId) {
        requireThread(threadId);
        ThreadMemory threadMemory = contextManager.latestThreadMemory(threadId).orElse(null);
        Set<?> compactedTurnIds = threadMemory == null ? Set.of() : new HashSet<>(threadMemory.sourceTurnIds());

        List<ConversationTurn> visibleTurns = conversationStore.turns(threadId).stream()
                .filter(turn -> !compactedTurnIds.contains(turn.turnId()))
                .toList();
        List<ConversationTurn> recentTurns = tail(visibleTurns, historyWindow);
        List<ConversationMessage> recentMessages = recentTurns.stream()
                .flatMap(this::messagesForTurn)
                .toList();
        List<ReconstructedTurnActivity> recentActivities = recentTurns.stream()
                .flatMap(this::activitiesForTurn)
                .toList();

        return new ReconstructedThreadContext(
                threadId,
                threadMemory,
                recentMessages,
                recentTurns,
                recentActivities,
                Instant.now());
    }

    private Stream<ConversationMessage> messagesForTurn(ConversationTurn turn) {
        ConversationMessage userMessage = new ConversationMessage(turn.turnId(),
                org.dean.codex.protocol.conversation.MessageRole.USER,
                turn.userInput(),
                turn.startedAt());
        if (turn.finalAnswer() == null || turn.finalAnswer().isBlank()) {
            return Stream.of(userMessage);
        }
        ConversationMessage assistantMessage = new ConversationMessage(turn.turnId(),
                org.dean.codex.protocol.conversation.MessageRole.ASSISTANT,
                turn.finalAnswer(),
                turn.completedAt() == null ? turn.startedAt() : turn.completedAt());
        return Stream.of(userMessage, assistantMessage);
    }

    private Stream<ReconstructedTurnActivity> activitiesForTurn(ConversationTurn turn) {
        if (!turn.items().isEmpty()) {
            return turn.items().stream()
                    .map(item -> new ReconstructedTurnActivity(
                            turn.turnId(),
                            item.getClass().getSimpleName(),
                            summarizeItem(item),
                            activityTimestamp(turn, item)));
        }
        return turn.events().stream()
                .map(event -> new ReconstructedTurnActivity(
                        turn.turnId(),
                        event.type(),
                        summarizeEvent(event),
                        activityTimestamp(turn, event)));
    }

    private Instant activityTimestamp(ConversationTurn turn, TurnItem item) {
        if (item instanceof UserMessageItem userMessageItem) {
            return userMessageItem.createdAt();
        }
        if (item instanceof AgentMessageItem agentMessageItem) {
            return agentMessageItem.createdAt();
        }
        if (item instanceof PlanItem planItem) {
            return planItem.createdAt();
        }
        if (item instanceof SkillUseItem skillUseItem) {
            return skillUseItem.createdAt();
        }
        if (item instanceof ToolCallItem toolCallItem) {
            return toolCallItem.createdAt();
        }
        if (item instanceof ToolResultItem toolResultItem) {
            return toolResultItem.createdAt();
        }
        if (item instanceof ApprovalItem approvalItem) {
            return approvalItem.createdAt();
        }
        if (item instanceof RuntimeErrorItem runtimeErrorItem) {
            return runtimeErrorItem.createdAt();
        }
        return turn.completedAt() == null ? turn.startedAt() : turn.completedAt();
    }

    private Instant activityTimestamp(ConversationTurn turn, TurnEvent event) {
        return turn.completedAt() == null ? turn.startedAt() : turn.completedAt();
    }

    private String summarizeItem(TurnItem item) {
        if (item instanceof UserMessageItem userMessageItem) {
            return "userMessage: " + userMessageItem.text();
        }
        if (item instanceof AgentMessageItem agentMessageItem) {
            return "agentMessage: " + agentMessageItem.text();
        }
        if (item instanceof PlanItem planItem) {
            if (planItem.plan() == null || planItem.plan().edits().isEmpty()) {
                return "plan: " + blankToPlaceholder(planItem.plan() == null ? "" : planItem.plan().summary());
            }
            String edits = planItem.plan().edits().stream()
                    .map(edit -> edit.type() + " " + blankToPlaceholder(edit.path()) + ": " + blankToPlaceholder(edit.description()))
                    .collect(Collectors.joining("; "));
            return "plan: " + blankToPlaceholder(planItem.plan().summary()) + " | " + edits;
        }
        if (item instanceof SkillUseItem skillUseItem) {
            return "skills: " + skillUseItem.skills().stream()
                    .map(skill -> skill.name())
                    .collect(Collectors.joining(", "));
        }
        if (item instanceof ToolCallItem toolCallItem) {
            return "toolCall: " + toolCallItem.toolName() + " " + blankToPlaceholder(toolCallItem.target());
        }
        if (item instanceof ToolResultItem toolResultItem) {
            return "toolResult: " + toolResultItem.toolName() + " " + toolResultItem.summary();
        }
        if (item instanceof ApprovalItem approvalItem) {
            return "approval: " + approvalItem.state() + " " + approvalItem.detail();
        }
        if (item instanceof RuntimeErrorItem runtimeErrorItem) {
            return "runtimeError: " + runtimeErrorItem.message();
        }
        return item.getClass().getSimpleName();
    }

    private String summarizeEvent(TurnEvent event) {
        return event.type() + ": " + event.detail();
    }

    private <T> List<T> tail(List<T> values, int maxEntries) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        int fromIndex = Math.max(0, values.size() - Math.max(1, maxEntries));
        return List.copyOf(values.subList(fromIndex, values.size()));
    }

    private String blankToPlaceholder(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }

    private void requireThread(ThreadId threadId) {
        if (threadId == null || !conversationStore.exists(threadId)) {
            throw new IllegalArgumentException("Unknown thread id: " + (threadId == null ? "<null>" : threadId.value()));
        }
    }
}
