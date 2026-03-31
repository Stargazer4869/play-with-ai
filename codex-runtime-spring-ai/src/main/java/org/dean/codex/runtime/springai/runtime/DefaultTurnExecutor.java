package org.dean.codex.runtime.springai.runtime;

import org.dean.codex.core.agent.CodexAgent;
import org.dean.codex.core.agent.TurnControl;
import org.dean.codex.core.agent.TurnExecutor;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.core.history.ThreadHistoryStore;
import org.dean.codex.protocol.conversation.ItemId;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.event.CodexTurnResult;
import org.dean.codex.protocol.history.HistoryMessageItem;
import org.dean.codex.protocol.history.ThreadHistoryItem;
import org.dean.codex.protocol.item.AgentMessageItem;
import org.dean.codex.protocol.item.RuntimeErrorItem;
import org.dean.codex.protocol.item.TurnItem;
import org.dean.codex.protocol.item.UserMessageItem;
import org.dean.codex.runtime.springai.history.ThreadHistoryMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Component
public class DefaultTurnExecutor implements TurnExecutor {

    private final CodexAgent codexAgent;
    private final ConversationStore conversationStore;
    private final ThreadHistoryStore threadHistoryStore;

    public DefaultTurnExecutor(CodexAgent codexAgent,
                               ConversationStore conversationStore,
                               ThreadHistoryStore threadHistoryStore) {
        this.codexAgent = codexAgent;
        this.conversationStore = conversationStore;
        this.threadHistoryStore = threadHistoryStore;
    }

    @Override
    public synchronized CodexTurnResult executeTurn(ThreadId threadId, String input) {
        return executeTurn(threadId, input, null);
    }

    @Override
    public synchronized CodexTurnResult executeTurn(ThreadId threadId, String input, Consumer<TurnItem> itemConsumer) {
        return executeTurn(threadId, input, itemConsumer, new TurnControl() { });
    }

    @Override
    public synchronized CodexTurnResult executeTurn(ThreadId threadId,
                                                    String input,
                                                    Consumer<TurnItem> itemConsumer,
                                                    TurnControl turnControl) {
        if (!conversationStore.exists(threadId)) {
            throw new IllegalArgumentException("Unknown thread id: " + (threadId == null ? "<null>" : threadId.value()));
        }

        Instant startedAt = Instant.now();
        TurnId turnId = conversationStore.startTurn(threadId, input, startedAt);
        return executeTurn(threadId, turnId, input, itemConsumer, turnControl);
    }

    @Override
    public synchronized CodexTurnResult executeTurn(ThreadId threadId,
                                                    TurnId turnId,
                                                    String input,
                                                    Consumer<TurnItem> itemConsumer,
                                                    TurnControl turnControl) {
        if (!conversationStore.exists(threadId)) {
            throw new IllegalArgumentException("Unknown thread id: " + (threadId == null ? "<null>" : threadId.value()));
        }

        conversationStore.turn(threadId, turnId);
        return runTurn(threadId, turnId, input, itemConsumer, true, turnControl);
    }

    @Override
    public synchronized CodexTurnResult resumeTurn(ThreadId threadId, TurnId turnId) {
        return resumeTurn(threadId, turnId, null);
    }

    @Override
    public synchronized CodexTurnResult resumeTurn(ThreadId threadId, TurnId turnId, Consumer<TurnItem> itemConsumer) {
        return resumeTurn(threadId, turnId, itemConsumer, new TurnControl() { });
    }

    @Override
    public synchronized CodexTurnResult resumeTurn(ThreadId threadId,
                                                   TurnId turnId,
                                                   Consumer<TurnItem> itemConsumer,
                                                   TurnControl turnControl) {
        if (!conversationStore.exists(threadId)) {
            throw new IllegalArgumentException("Unknown thread id: " + (threadId == null ? "<null>" : threadId.value()));
        }

        var turn = conversationStore.turn(threadId, turnId);
        if (turn.status() != TurnStatus.AWAITING_APPROVAL) {
            throw new IllegalArgumentException("Turn is not awaiting approval: " + turnId.value());
        }

        conversationStore.updateTurnStatus(threadId, turnId, TurnStatus.RUNNING, Instant.now());
        return runTurn(threadId, turnId, turn.userInput(), itemConsumer, false, turnControl);
    }

    private CodexTurnResult runTurn(ThreadId threadId,
                                    TurnId turnId,
                                    String input,
                                    Consumer<TurnItem> itemConsumer,
                                    boolean includeUserMessageItem,
                                    TurnControl turnControl) {
        try {
            List<TurnItem> streamedItems = new ArrayList<>();
            AtomicBoolean assistantMessageRecorded = new AtomicBoolean(false);
            if (includeUserMessageItem) {
                TurnItem userMessageItem = new UserMessageItem(new ItemId(java.util.UUID.randomUUID().toString()), input, Instant.now());
                streamedItems.add(userMessageItem);
                appendTurnItems(threadId, turnId, List.of(userMessageItem));
                emit(itemConsumer, userMessageItem);
            }
            CodexTurnResult result = codexAgent.handleTurn(threadId, turnId, input, item -> {
                streamedItems.add(item);
                appendTurnItems(threadId, turnId, List.of(item));
                if (item instanceof AgentMessageItem) {
                    assistantMessageRecorded.set(true);
                }
                emit(itemConsumer, item);
            }, turnControl == null ? new TurnControl() { } : turnControl);
            int streamedNonUserCount = Math.max(0, streamedItems.size() - (includeUserMessageItem ? 1 : 0));
            if (streamedNonUserCount < result.items().size()) {
                List<TurnItem> remainingItems = result.items().subList(streamedNonUserCount, result.items().size());
                appendTurnItems(threadId, turnId, remainingItems);
                if (!assistantMessageRecorded.get()) {
                    assistantMessageRecorded.set(remainingItems.stream().anyMatch(AgentMessageItem.class::isInstance));
                }
                remainingItems.forEach(item -> emit(itemConsumer, item));
            }
            if (result.status() == TurnStatus.COMPLETED && !assistantMessageRecorded.get()
                    && result.finalAnswer() != null && !result.finalAnswer().isBlank()) {
                appendHistoryItems(threadId, turnId, List.of(new HistoryMessageItem(
                        turnId,
                        org.dean.codex.protocol.conversation.MessageRole.ASSISTANT,
                        result.finalAnswer(),
                        Instant.now())));
            }
            if (result.status() == TurnStatus.AWAITING_APPROVAL) {
                conversationStore.updateTurnStatus(threadId, turnId, TurnStatus.AWAITING_APPROVAL, Instant.now());
            }
            else {
                conversationStore.completeTurn(
                        threadId,
                        turnId,
                        result.status(),
                        result.finalAnswer(),
                        Instant.now());
            }
            return result;
        }
        catch (Exception exception) {
            Instant failedAt = Instant.now();
            RuntimeErrorItem failureItem = new RuntimeErrorItem(
                    new ItemId(UUID.randomUUID().toString()),
                    exception.getMessage() == null || exception.getMessage().isBlank() ? "Unknown runtime error" : exception.getMessage(),
                    failedAt);
            appendTurnItems(threadId, turnId, List.of(failureItem));
            String finalAnswer = "The Codex runtime hit an error: " + failureItem.message();
            conversationStore.completeTurn(threadId, turnId, TurnStatus.FAILED, finalAnswer, failedAt);
            return new CodexTurnResult(threadId, turnId, TurnStatus.FAILED, List.of(failureItem), finalAnswer);
        }
    }

    private void appendTurnItems(ThreadId threadId, TurnId turnId, List<TurnItem> items) {
        conversationStore.appendTurnItems(threadId, turnId, items);
        appendHistory(threadId, turnId, items);
    }

    private void appendHistory(ThreadId threadId, TurnId turnId, List<? extends TurnItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        List<TurnItem> turnItems = new ArrayList<>(items.size());
        turnItems.addAll(items);
        List<ThreadHistoryItem> historyItems = ThreadHistoryMapper.map(turnId, turnItems);
        if (!historyItems.isEmpty()) {
            threadHistoryStore.append(threadId, historyItems);
        }
    }

    private void appendHistoryItems(ThreadId threadId, TurnId turnId, List<ThreadHistoryItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        List<ThreadHistoryItem> historyItems = new ArrayList<>(items.size());
        for (ThreadHistoryItem item : items) {
            historyItems.add(item instanceof HistoryMessageItem historyMessageItem
                    ? new HistoryMessageItem(turnId, historyMessageItem.role(), historyMessageItem.text(), historyMessageItem.createdAt())
                    : item);
        }
        threadHistoryStore.append(threadId, historyItems);
    }

    private void emit(Consumer<TurnItem> itemConsumer, TurnItem item) {
        if (itemConsumer != null) {
            itemConsumer.accept(item);
        }
    }
}
