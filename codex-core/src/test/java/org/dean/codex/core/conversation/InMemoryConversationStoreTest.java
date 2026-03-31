package org.dean.codex.core.conversation;

import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.ItemId;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.item.ToolCallItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
