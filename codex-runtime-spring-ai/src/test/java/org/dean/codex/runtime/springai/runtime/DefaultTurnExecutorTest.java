package org.dean.codex.runtime.springai.runtime;

import org.dean.codex.core.agent.CodexAgent;
import org.dean.codex.core.agent.TurnControl;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.core.conversation.InMemoryConversationStore;
import org.dean.codex.protocol.conversation.ItemId;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.event.CodexTurnResult;
import org.dean.codex.protocol.item.ApprovalItem;
import org.dean.codex.protocol.item.ApprovalState;
import org.dean.codex.protocol.item.ToolCallItem;
import org.dean.codex.protocol.item.ToolResultItem;
import org.dean.codex.protocol.item.TurnItem;
import org.dean.codex.protocol.item.UserMessageItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultTurnExecutorTest {

    @Test
    void executesTurnAndPersistsResultToConversationStore() {
        ConversationStore store = new InMemoryConversationStore();
        ThreadId threadId = store.createThread("Executor thread");
        DefaultTurnExecutor executor = new DefaultTurnExecutor(new StubCodexAgent(), store);

        CodexTurnResult result = executor.executeTurn(threadId, "hello");

        assertEquals(TurnStatus.COMPLETED, result.status());
        assertEquals(1, store.turns(threadId).size());
        assertEquals("hello", store.turns(threadId).get(0).userInput());
        assertEquals("done", store.turns(threadId).get(0).finalAnswer());
        assertEquals(2, store.turns(threadId).get(0).items().size());
    }

    @Test
    void resumesAwaitingApprovalTurnWithoutCreatingNewTurn() {
        ConversationStore store = new InMemoryConversationStore();
        ThreadId threadId = store.createThread("Executor thread");
        DefaultTurnExecutor executor = new DefaultTurnExecutor(new PausingCodexAgent(), store);

        CodexTurnResult first = executor.executeTurn(threadId, "run tests");
        CodexTurnResult resumed = executor.resumeTurn(threadId, first.turnId());

        assertEquals(TurnStatus.AWAITING_APPROVAL, first.status());
        assertEquals(TurnStatus.COMPLETED, resumed.status());
        assertEquals(1, store.turns(threadId).size());
        assertEquals(TurnStatus.COMPLETED, store.turn(threadId, first.turnId()).status());
        assertEquals("done after approval", store.turn(threadId, first.turnId()).finalAnswer());
        assertTrue(store.turn(threadId, first.turnId()).items().size() >= 2);
    }

    @Test
    void streamsEventsWhilePersistingThemToConversationStore() {
        ConversationStore store = new InMemoryConversationStore();
        ThreadId threadId = store.createThread("Executor thread");
        DefaultTurnExecutor executor = new DefaultTurnExecutor(new StreamingCodexAgent(), store);
        List<TurnItem> streamedItems = new ArrayList<>();

        CodexTurnResult result = executor.executeTurn(threadId, "inspect repo", streamedItems::add);

        assertEquals(TurnStatus.COMPLETED, result.status());
        assertEquals(3, streamedItems.size());
        assertTrue(streamedItems.get(0) instanceof UserMessageItem);
        assertTrue(streamedItems.get(1) instanceof ToolCallItem);
        assertTrue(streamedItems.get(2) instanceof ToolResultItem);
        assertEquals(3, store.turns(threadId).get(0).items().size());
    }

    @Test
    void persistsInterruptedTurnsAsTerminalState() {
        ConversationStore store = new InMemoryConversationStore();
        ThreadId threadId = store.createThread("Executor thread");
        DefaultTurnExecutor executor = new DefaultTurnExecutor(new InterruptibleCodexAgent(), store);
        TurnControl turnControl = new TurnControl() {
            @Override
            public boolean interruptionRequested() {
                return true;
            }
        };

        CodexTurnResult result = executor.executeTurn(threadId, "interrupt me", item -> { }, turnControl);

        assertEquals(TurnStatus.INTERRUPTED, result.status());
        assertEquals(TurnStatus.INTERRUPTED, store.turn(threadId, result.turnId()).status());
        assertEquals("Turn interrupted.", store.turn(threadId, result.turnId()).finalAnswer());
    }

    private static final class StubCodexAgent implements CodexAgent {

        @Override
        public CodexTurnResult handleTurn(ThreadId threadId, TurnId turnId, String input) {
            return new CodexTurnResult(
                    threadId,
                    turnId,
                    TurnStatus.COMPLETED,
                    List.of(new ToolCallItem(new ItemId("event-1"), "READ_FILE", "README.md", Instant.now())),
                    "done");
        }
    }

    private static final class PausingCodexAgent implements CodexAgent {

        private final AtomicInteger invocationCount = new AtomicInteger();

        @Override
        public CodexTurnResult handleTurn(ThreadId threadId, TurnId turnId, String input) {
            if (invocationCount.incrementAndGet() == 1) {
                return new CodexTurnResult(
                        threadId,
                        turnId,
                        TurnStatus.AWAITING_APPROVAL,
                        List.of(new ApprovalItem(new ItemId("event-1"), ApprovalState.REQUIRED, "abc123", "mvn test",
                                "Approval abc123 required", Instant.now())),
                        "Approval required");
            }
            return new CodexTurnResult(
                    threadId,
                    turnId,
                    TurnStatus.COMPLETED,
                    List.of(new ToolResultItem(new ItemId("event-2"), "RUN_COMMAND", "success=true", Instant.now())),
                    "done after approval");
        }
    }

    private static final class StreamingCodexAgent implements CodexAgent {

        @Override
        public CodexTurnResult handleTurn(ThreadId threadId,
                                          TurnId turnId,
                                          String input,
                                          Consumer<TurnItem> eventConsumer) {
            ToolCallItem call = new ToolCallItem(new ItemId("event-1"), "SEARCH_FILES", "pom.xml", Instant.now());
            ToolResultItem result = new ToolResultItem(new ItemId("event-2"), "SEARCH_FILES", "success=true", Instant.now());
            eventConsumer.accept(call);
            eventConsumer.accept(result);
            return new CodexTurnResult(threadId, turnId, TurnStatus.COMPLETED, List.of(call, result), "done");
        }

        @Override
        public CodexTurnResult handleTurn(ThreadId threadId, TurnId turnId, String input) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class InterruptibleCodexAgent implements CodexAgent {

        @Override
        public CodexTurnResult handleTurn(ThreadId threadId,
                                          TurnId turnId,
                                          String input,
                                          Consumer<TurnItem> eventConsumer,
                                          TurnControl turnControl) {
            if (turnControl.interruptionRequested()) {
                return new CodexTurnResult(threadId, turnId, TurnStatus.INTERRUPTED, List.of(), "Turn interrupted.");
            }
            return new CodexTurnResult(threadId, turnId, TurnStatus.COMPLETED, List.of(), "done");
        }

        @Override
        public CodexTurnResult handleTurn(ThreadId threadId, TurnId turnId, String input) {
            throw new UnsupportedOperationException();
        }
    }
}
