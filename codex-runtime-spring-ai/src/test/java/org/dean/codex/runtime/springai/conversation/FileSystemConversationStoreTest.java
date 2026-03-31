package org.dean.codex.runtime.springai.conversation;

import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.protocol.conversation.ItemId;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.item.ToolCallItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemConversationStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsThreadsAndTurnsAcrossStoreInstances() {
        ConversationStore firstStore = new FileSystemConversationStore(tempDir);
        ThreadId threadId = firstStore.createThread("Persistent thread");
        Instant startedAt = Instant.parse("2026-03-25T00:00:00Z");
        TurnId turnId = firstStore.startTurn(threadId, "hello", startedAt);
        firstStore.appendTurnItems(threadId, turnId, List.of(new ToolCallItem(
                new ItemId("event-1"),
                "READ_FILE",
                "README.md",
                startedAt.plusSeconds(1))));
        firstStore.completeTurn(threadId, turnId, TurnStatus.COMPLETED, "done", startedAt.plusSeconds(2));

        ConversationStore secondStore = new FileSystemConversationStore(tempDir);
        assertTrue(secondStore.exists(threadId));
        assertEquals(1, secondStore.listThreads().size());
        assertEquals("Persistent thread", secondStore.listThreads().get(0).title());
        assertEquals(1, secondStore.listThreads().get(0).turnCount());
        assertEquals(1, secondStore.turns(threadId).size());
        assertEquals("hello", secondStore.turns(threadId).get(0).userInput());
        assertEquals("done", secondStore.turns(threadId).get(0).finalAnswer());
        assertEquals(1, secondStore.turns(threadId).get(0).items().size());
    }
}
