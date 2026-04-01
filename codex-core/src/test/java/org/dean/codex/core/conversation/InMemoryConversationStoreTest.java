package org.dean.codex.core.conversation;

import org.dean.codex.protocol.agent.AgentStatus;
import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.ItemId;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.ThreadSummary;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.item.ToolCallItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryConversationStoreTest {

    @Test
    void createsThreadsAndStoresStructuredTurns() {
        ConversationStore store = new InMemoryConversationStore();

        ThreadId threadId = store.createThread("Example thread");
        Instant startedAt = Instant.parse("2026-03-25T00:00:00Z");
        TurnId turnId = store.startTurn(threadId, "hello", startedAt);
        store.appendTurnItems(threadId, turnId, List.of(new ToolCallItem(
                new ItemId("event-1"),
                "READ_FILE",
                "README.md",
                startedAt.plusSeconds(1))));
        store.completeTurn(threadId, turnId, TurnStatus.COMPLETED, "done", startedAt.plusSeconds(2));

        assertTrue(store.exists(threadId));
        assertEquals(2, store.messages(threadId).size());
        assertEquals("Example thread", store.listThreads().get(0).title());
        assertEquals(1, store.listThreads().get(0).turnCount());
        ConversationTurn turn = store.turns(threadId).get(0);
        assertEquals("hello", turn.userInput());
        assertEquals("done", turn.finalAnswer());
        assertEquals(TurnStatus.COMPLETED, turn.status());
        assertEquals(1, turn.items().size());
    }

    @Test
    void archivesUnarchivesAndRollsBackTurns() {
        InMemoryConversationStore store = new InMemoryConversationStore();

        ThreadId threadId = store.createThread("Lifecycle thread");
        Instant base = Instant.parse("2026-04-01T00:00:00Z");
        TurnId firstTurn = store.startTurn(threadId, "inspect repo", base);
        store.completeTurn(threadId, firstTurn, TurnStatus.COMPLETED, "inspected", base.plusSeconds(1));
        TurnId secondTurn = store.startTurn(threadId, "run tests", base.plusSeconds(2));
        store.completeTurn(threadId, secondTurn, TurnStatus.COMPLETED, "tested", base.plusSeconds(3));

        ThreadSummary archived = store.archiveThread(threadId);
        assertTrue(archived.archived());

        ThreadSummary unarchived = store.unarchiveThread(threadId);
        assertFalse(unarchived.archived());

        ThreadSummary rolledBack = store.rollbackThread(threadId, 1);
        assertEquals(1, rolledBack.turnCount());
        assertEquals("inspect repo", rolledBack.preview());
        assertEquals(1, store.turns(threadId).size());
        assertEquals("inspected", store.turn(threadId, firstTurn).finalAnswer());
    }

    @Test
    void updatesAgentRelationshipAndClosedState() {
        InMemoryConversationStore store = new InMemoryConversationStore();

        ThreadId parentThreadId = store.createThread("Parent thread");
        ThreadId childThreadId = store.createThread("Child thread");

        ThreadSummary attached = store.updateAgentThread(
                childThreadId,
                parentThreadId,
                1,
                null,
                "worker-1",
                "worker",
                "root/worker-1");
        assertEquals(parentThreadId, attached.parentThreadId());
        assertEquals(1, attached.agentDepth());
        assertEquals(AgentStatus.IDLE, attached.agentStatus());

        ThreadSummary closed = store.updateAgentThread(
                childThreadId,
                parentThreadId,
                1,
                Instant.parse("2026-04-01T00:00:00Z"),
                "worker-1",
                "worker",
                "root/worker-1");
        assertEquals(AgentStatus.SHUTDOWN, closed.agentStatus());
        assertEquals(parentThreadId, store.listThreads().stream()
                .filter(summary -> summary.threadId().equals(childThreadId))
                .findFirst()
                .orElseThrow()
                .parentThreadId());
    }
}
