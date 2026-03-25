package org.dean.codex.runtime.springai.runtime;

import org.dean.codex.core.agent.CodexAgent;
import org.dean.codex.core.agent.TurnExecutor;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.protocol.conversation.ItemId;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.event.CodexTurnResult;
import org.dean.codex.protocol.event.TurnEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class DefaultTurnExecutor implements TurnExecutor {

    private final CodexAgent codexAgent;
    private final ConversationStore conversationStore;

    public DefaultTurnExecutor(CodexAgent codexAgent, ConversationStore conversationStore) {
        this.codexAgent = codexAgent;
        this.conversationStore = conversationStore;
    }

    @Override
    public synchronized CodexTurnResult executeTurn(ThreadId threadId, String input) {
        if (!conversationStore.exists(threadId)) {
            throw new IllegalArgumentException("Unknown thread id: " + (threadId == null ? "<null>" : threadId.value()));
        }

        Instant startedAt = Instant.now();
        TurnId turnId = conversationStore.startTurn(threadId, input, startedAt);
        return runTurn(threadId, turnId, input);
    }

    @Override
    public synchronized CodexTurnResult resumeTurn(ThreadId threadId, TurnId turnId) {
        if (!conversationStore.exists(threadId)) {
            throw new IllegalArgumentException("Unknown thread id: " + (threadId == null ? "<null>" : threadId.value()));
        }

        var turn = conversationStore.turn(threadId, turnId);
        if (turn.status() != TurnStatus.AWAITING_APPROVAL) {
            throw new IllegalArgumentException("Turn is not awaiting approval: " + turnId.value());
        }

        conversationStore.updateTurnStatus(threadId, turnId, TurnStatus.RUNNING, Instant.now());
        return runTurn(threadId, turnId, turn.userInput());
    }

    private CodexTurnResult runTurn(ThreadId threadId, TurnId turnId, String input) {
        try {
            CodexTurnResult result = codexAgent.handleTurn(threadId, turnId, input);
            conversationStore.appendTurnEvents(threadId, turnId, result.events());
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
            TurnEvent failureEvent = new TurnEvent(
                    new ItemId(UUID.randomUUID().toString()),
                    "runtime.error",
                    exception.getMessage() == null || exception.getMessage().isBlank() ? "Unknown runtime error" : exception.getMessage(),
                    failedAt);
            conversationStore.appendTurnEvents(threadId, turnId, List.of(failureEvent));
            String finalAnswer = "The Codex runtime hit an error: " + failureEvent.detail();
            conversationStore.completeTurn(threadId, turnId, TurnStatus.FAILED, finalAnswer, failedAt);
            return new CodexTurnResult(threadId, turnId, TurnStatus.FAILED, List.of(failureEvent), finalAnswer);
        }
    }
}
