package org.dean.codex.runtime.springai.context;

import org.dean.codex.core.context.ContextManager;
import org.dean.codex.core.context.ThreadContextReconstructionService;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.core.history.ThreadHistoryStore;
import org.dean.codex.protocol.context.ReconstructedThreadContext;
import org.dean.codex.protocol.context.ReconstructedTurnActivity;
import org.dean.codex.protocol.context.ThreadMemory;
import org.dean.codex.protocol.conversation.MessageRole;
import org.dean.codex.protocol.conversation.ConversationMessage;
import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.history.HistoryApprovalItem;
import org.dean.codex.protocol.history.HistoryCompactionSummaryItem;
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
import org.dean.codex.runtime.springai.history.ThreadHistoryReplay;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DefaultThreadContextReconstructionService implements ThreadContextReconstructionService {

    private final ConversationStore conversationStore;
    private final ThreadHistoryStore threadHistoryStore;
    private final ContextManager contextManager;
    private final int historyWindow;

    public DefaultThreadContextReconstructionService(ConversationStore conversationStore,
                                                     ThreadHistoryStore threadHistoryStore,
                                                     ContextManager contextManager,
                                                     int historyWindow) {
        this.conversationStore = conversationStore;
        this.threadHistoryStore = threadHistoryStore;
        this.contextManager = contextManager;
        this.historyWindow = Math.max(1, historyWindow);
    }

    @Override
    public ReconstructedThreadContext reconstruct(ThreadId threadId) {
        requireThread(threadId);
        ThreadMemory threadMemory = contextManager.latestThreadMemory(threadId).orElse(null);
        List<ConversationTurn> allTurns = conversationStore.turns(threadId);
        List<ThreadHistoryItem> visibleHistory = ThreadHistoryReplay.replayVisibleHistory(threadHistoryStore.read(threadId));

        if (visibleHistory.isEmpty()) {
            List<ConversationTurn> recentTurns = tail(allTurns, historyWindow);
            return new ReconstructedThreadContext(
                    threadId,
                    threadMemory,
                    recentTurns.stream().flatMap(this::messagesForTurn).toList(),
                    recentTurns,
                    recentTurns.stream().flatMap(this::activitiesForTurn).toList(),
                    Instant.now());
        }

        List<HistoryCompactionSummaryItem> summaryItems = visibleHistory.stream()
                .filter(HistoryCompactionSummaryItem.class::isInstance)
                .map(HistoryCompactionSummaryItem.class::cast)
                .toList();
        List<ThreadHistoryItem> normalHistory = visibleHistory.stream()
                .filter(item -> !(item instanceof HistoryCompactionSummaryItem))
                .toList();
        LinkedHashSet<ConversationTurn> visibleTurnSet = new LinkedHashSet<>();
        for (ThreadHistoryItem item : normalHistory) {
            ConversationTurn turn = turnForHistoryItem(allTurns, item);
            if (turn != null) {
                visibleTurnSet.add(turn);
            }
        }
        List<ConversationTurn> visibleTurns = List.copyOf(visibleTurnSet);
        List<ConversationTurn> recentTurns = tail(visibleTurns.isEmpty() ? allTurns : visibleTurns, historyWindow);
        List<ConversationMessage> recentMessages = new ArrayList<>();
        List<ReconstructedTurnActivity> recentActivities = new ArrayList<>();
        for (HistoryCompactionSummaryItem summaryItem : summaryItems) {
            recentMessages.add(summaryMessage(summaryItem));
            recentActivities.add(summaryActivity(summaryItem));
        }
        for (ThreadHistoryItem item : normalHistory) {
            ConversationTurn turn = turnForHistoryItem(allTurns, item);
            if (turn == null || !recentTurns.contains(turn)) {
                continue;
            }
            messageForItem(item, turn).ifPresent(recentMessages::add);
            activityForItem(item, turn).ifPresent(recentActivities::add);
        }

        return new ReconstructedThreadContext(
                threadId,
                threadMemory,
                recentMessages,
                recentTurns,
                recentActivities,
                Instant.now());
    }

    private Optional<ConversationMessage> messageForItem(ThreadHistoryItem item, ConversationTurn turn) {
        if (turn == null) {
            return Optional.empty();
        }
        if (item instanceof HistoryMessageItem historyMessageItem) {
            return Optional.of(new ConversationMessage(
                    turn.turnId(),
                    historyMessageItem.role(),
                    historyMessageItem.text(),
                    historyMessageItem.createdAt()));
        }
        return Optional.empty();
    }

    private ConversationMessage summaryMessage(HistoryCompactionSummaryItem summaryItem) {
        return new ConversationMessage(
                summaryItem.anchorTurnId(),
                MessageRole.ASSISTANT,
                summaryItem.summaryText(),
                summaryItem.createdAt());
    }

    private ReconstructedTurnActivity summaryActivity(HistoryCompactionSummaryItem summaryItem) {
        return new ReconstructedTurnActivity(
                summaryItem.anchorTurnId(),
                "historyCompactionSummary",
                "compactionSummary: " + summaryItem.summaryText(),
                summaryItem.createdAt());
    }

    private Optional<ReconstructedTurnActivity> activityForItem(ThreadHistoryItem item, ConversationTurn turn) {
        if (turn == null) {
            return Optional.empty();
        }
        return Optional.of(new ReconstructedTurnActivity(
                turn.turnId(),
                item.getClass().getSimpleName(),
                summarizeHistoryItem(item),
                item.createdAt()));
    }

    private ConversationTurn turnForHistoryItem(List<ConversationTurn> turns, ThreadHistoryItem item) {
        if (turns == null || turns.isEmpty() || item == null) {
            return null;
        }
        if (item instanceof HistoryCompactionSummaryItem summaryItem) {
            return turnForId(turns, summaryItem.anchorTurnId());
        }
        if (item.turnId() != null && item.turnId().value() != null && !item.turnId().value().isBlank()) {
            ConversationTurn turn = turnForId(turns, item.turnId());
            if (turn != null) {
                return turn;
            }
        }
        return turnForItem(turns, item.createdAt());
    }

    private ConversationTurn turnForId(List<ConversationTurn> turns, org.dean.codex.protocol.conversation.TurnId turnId) {
        if (turns == null || turns.isEmpty() || turnId == null) {
            return null;
        }
        for (ConversationTurn turn : turns) {
            if (turn.turnId().equals(turnId)) {
                return turn;
            }
        }
        return null;
    }

    private Stream<ConversationMessage> messagesForTurn(ConversationTurn turn) {
        ConversationMessage userMessage = new ConversationMessage(turn.turnId(),
                MessageRole.USER,
                turn.userInput(),
                turn.startedAt());
        if (turn.finalAnswer() == null || turn.finalAnswer().isBlank()) {
            return Stream.of(userMessage);
        }
        ConversationMessage assistantMessage = new ConversationMessage(turn.turnId(),
                MessageRole.ASSISTANT,
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

    private Instant activityTimestamp(ConversationTurn turn, org.dean.codex.protocol.event.TurnEvent event) {
        return turn.completedAt() == null ? turn.startedAt() : turn.completedAt();
    }

    private String summarizeHistoryItem(ThreadHistoryItem item) {
        if (item instanceof HistoryMessageItem historyMessageItem) {
            return historyMessageItem.role().name().toLowerCase() + ": " + historyMessageItem.text();
        }
        if (item instanceof HistoryPlanItem historyPlanItem) {
            if (historyPlanItem.plan() == null || historyPlanItem.plan().edits().isEmpty()) {
                return "plan: " + blankToPlaceholder(historyPlanItem.plan() == null ? "" : historyPlanItem.plan().summary());
            }
            String edits = historyPlanItem.plan().edits().stream()
                    .map(edit -> edit.type() + " " + blankToPlaceholder(edit.path()) + ": " + blankToPlaceholder(edit.description()))
                    .collect(Collectors.joining("; "));
            return "plan: " + blankToPlaceholder(historyPlanItem.plan().summary()) + " | " + edits;
        }
        if (item instanceof HistorySkillUseItem historySkillUseItem) {
            return "skills: " + historySkillUseItem.skills().stream()
                    .map(skill -> skill.name())
                    .collect(Collectors.joining(", "));
        }
        if (item instanceof HistoryToolCallItem historyToolCallItem) {
            return "toolCall: " + historyToolCallItem.toolName() + " " + blankToPlaceholder(historyToolCallItem.target());
        }
        if (item instanceof HistoryToolResultItem historyToolResultItem) {
            return "toolResult: " + historyToolResultItem.toolName() + " " + historyToolResultItem.summary();
        }
        if (item instanceof HistoryApprovalItem historyApprovalItem) {
            return "approval: " + historyApprovalItem.state() + " " + historyApprovalItem.detail();
        }
        if (item instanceof HistoryRuntimeErrorItem historyRuntimeErrorItem) {
            return "runtimeError: " + historyRuntimeErrorItem.message();
        }
        return item.getClass().getSimpleName();
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

    private String summarizeEvent(org.dean.codex.protocol.event.TurnEvent event) {
        return event.type() + ": " + event.detail();
    }

    private <T> List<T> tail(List<T> values, int maxEntries) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        int fromIndex = Math.max(0, values.size() - Math.max(1, maxEntries));
        return List.copyOf(values.subList(fromIndex, values.size()));
    }

    private ConversationTurn turnForItem(List<ConversationTurn> turns, Instant itemTimestamp) {
        if (turns == null || turns.isEmpty()) {
            return null;
        }
        ConversationTurn selected = turns.get(0);
        for (ConversationTurn turn : turns) {
            if (turn.startedAt() == null) {
                continue;
            }
            if (!turn.startedAt().isAfter(itemTimestamp)) {
                selected = turn;
                continue;
            }
            return selected;
        }
        return selected;
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
