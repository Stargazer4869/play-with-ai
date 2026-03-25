package org.dean.codex.runtime.springai.runtime;

import org.dean.codex.core.agent.CodexAgent;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.core.conversation.InMemoryConversationStore;
import org.dean.codex.protocol.conversation.ItemId;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.event.CodexTurnResult;
import org.dean.codex.protocol.event.TurnEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
        assertEquals(1, store.turns(threadId).get(0).events().size());
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
        assertTrue(store.turn(threadId, first.turnId()).events().size() >= 2);
    }

    private static final class StubCodexAgent implements CodexAgent {

        @Override
        public CodexTurnResult handleTurn(ThreadId threadId, TurnId turnId, String input) {
            return new CodexTurnResult(
                    threadId,
                    turnId,
                    TurnStatus.COMPLETED,
                    List.of(new TurnEvent(new ItemId("event-1"), "tool.call", "READ_FILE path=README.md", Instant.now())),
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
                        List.of(new TurnEvent(new ItemId("event-1"), "approval.required", "Approval abc123 required", Instant.now())),
                        "Approval required");
            }
            return new CodexTurnResult(
                    threadId,
                    turnId,
                    TurnStatus.COMPLETED,
                    List.of(new TurnEvent(new ItemId("event-2"), "tool.result", "RUN_COMMAND success=true", Instant.now())),
                    "done after approval");
        }
    }
}
