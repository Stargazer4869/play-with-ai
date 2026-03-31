package org.dean.codex.runtime.springai.context;

import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.core.conversation.InMemoryConversationStore;
import org.dean.codex.protocol.context.ThreadMemory;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemContextManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void compactsOlderTurnsIntoSeparateThreadMemoryFile() {
        ConversationStore conversationStore = new InMemoryConversationStore();
        ThreadId threadId = conversationStore.createThread("Compaction thread");

        TurnId firstTurn = conversationStore.startTurn(threadId, "Inspect repo", Instant.parse("2026-03-25T00:00:00Z"));
        conversationStore.completeTurn(threadId, firstTurn, TurnStatus.COMPLETED, "Repo inspected", Instant.parse("2026-03-25T00:00:05Z"));

        TurnId secondTurn = conversationStore.startTurn(threadId, "Run tests", Instant.parse("2026-03-25T00:01:00Z"));
        conversationStore.completeTurn(threadId, secondTurn, TurnStatus.COMPLETED, "Tests passed", Instant.parse("2026-03-25T00:01:05Z"));

        FileSystemContextManager contextManager = new FileSystemContextManager(conversationStore, tempDir, 1);

        ThreadMemory threadMemory = contextManager.compactThread(threadId);

        assertEquals(1, threadMemory.compactedTurnCount());
        assertEquals(firstTurn, threadMemory.sourceTurnIds().get(0));
        assertTrue(threadMemory.summary().contains("Inspect repo"));
        assertTrue(contextManager.latestThreadMemory(threadId).isPresent());
        assertEquals(threadMemory.memoryId(), contextManager.latestThreadMemory(threadId).orElseThrow().memoryId());
    }
}
