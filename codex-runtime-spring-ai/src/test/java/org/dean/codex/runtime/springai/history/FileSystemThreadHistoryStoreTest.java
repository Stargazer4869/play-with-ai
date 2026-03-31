package org.dean.codex.runtime.springai.history;

import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.runtime.springai.conversation.FileSystemConversationStore;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.history.CompactedHistoryItem;
import org.dean.codex.protocol.history.CompactionStrategy;
import org.dean.codex.protocol.history.HistoryMessageItem;
import org.dean.codex.protocol.history.HistoryToolCallItem;
import org.dean.codex.protocol.history.HistoryToolResultItem;
import org.dean.codex.protocol.history.ThreadHistoryItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemThreadHistoryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsHistoryAcrossStoreInstances() {
        ConversationStore conversationStore = new FileSystemConversationStore(tempDir);
        ThreadId threadId = conversationStore.createThread("History thread");
        FileSystemThreadHistoryStore firstStore = new FileSystemThreadHistoryStore(conversationStore, tempDir);
        TurnId turnId = new TurnId("turn-1");

        List<ThreadHistoryItem> history = List.of(
                new HistoryMessageItem(turnId, org.dean.codex.protocol.conversation.MessageRole.USER, "Inspect repo", Instant.parse("2026-03-31T00:00:00Z")),
                new HistoryToolCallItem(turnId, "READ_FILE", "README.md", Instant.parse("2026-03-31T00:00:01Z")),
                new HistoryToolResultItem(turnId, "READ_FILE", "success path=README.md", Instant.parse("2026-03-31T00:00:02Z")));
        firstStore.append(threadId, history);

        ConversationStore secondConversationStore = new FileSystemConversationStore(tempDir);
        FileSystemThreadHistoryStore secondStore = new FileSystemThreadHistoryStore(secondConversationStore, tempDir);

        List<ThreadHistoryItem> restored = secondStore.read(threadId);

        assertEquals(history, restored);
        assertEquals("Inspect repo", ((HistoryMessageItem) restored.get(0)).text());
        assertEquals("READ_FILE", ((HistoryToolCallItem) restored.get(1)).toolName());
        assertEquals("success path=README.md", ((HistoryToolResultItem) restored.get(2)).summary());
        assertEquals(turnId, restored.get(0).turnId());
    }

    @Test
    void preservesDeterministicAppendOrdering() {
        ConversationStore conversationStore = new FileSystemConversationStore(tempDir);
        ThreadId threadId = conversationStore.createThread("Ordering thread");
        FileSystemThreadHistoryStore store = new FileSystemThreadHistoryStore(conversationStore, tempDir);

        TurnId turnId = new TurnId("turn-1");
        ThreadHistoryItem first = new HistoryMessageItem(turnId, org.dean.codex.protocol.conversation.MessageRole.USER, "first", Instant.parse("2026-03-31T00:00:00Z"));
        ThreadHistoryItem second = new HistoryToolCallItem(turnId, "RUN_COMMAND", "git status", Instant.parse("2026-03-31T00:00:01Z"));
        ThreadHistoryItem third = new HistoryToolResultItem(turnId, "RUN_COMMAND", "exitCode=0", Instant.parse("2026-03-31T00:00:02Z"));
        store.append(threadId, List.of(first, second));
        store.append(threadId, List.of(third));

        List<ThreadHistoryItem> restored = store.read(threadId);

        assertEquals(List.of(first, second, third), restored);
    }

    @Test
    void replaceOverwritesExistingHistoryAtomically() {
        ConversationStore conversationStore = new FileSystemConversationStore(tempDir);
        ThreadId threadId = conversationStore.createThread("Replace thread");
        FileSystemThreadHistoryStore store = new FileSystemThreadHistoryStore(conversationStore, tempDir);
        TurnId retainedTurn = new TurnId("turn-2");

        store.append(threadId, List.of(
                new HistoryMessageItem(new TurnId("turn-1"), org.dean.codex.protocol.conversation.MessageRole.USER, "before", Instant.parse("2026-03-31T00:00:00Z")),
                new HistoryToolCallItem(new TurnId("turn-1"), "SEARCH_FILES", "src", Instant.parse("2026-03-31T00:00:01Z"))));
        List<ThreadHistoryItem> replacement = List.of(
                new CompactedHistoryItem(
                        "Compacted earlier thread context.",
                        List.of(new HistoryMessageItem(retainedTurn, org.dean.codex.protocol.conversation.MessageRole.USER, "after", Instant.parse("2026-03-31T00:00:02Z"))),
                        Instant.parse("2026-03-31T00:00:03Z"),
                        CompactionStrategy.LOCAL_SUMMARY),
                new HistoryMessageItem(retainedTurn, org.dean.codex.protocol.conversation.MessageRole.USER, "current", Instant.parse("2026-03-31T00:00:04Z")));

        store.replace(threadId, replacement);

        ConversationStore restartedConversationStore = new FileSystemConversationStore(tempDir);
        FileSystemThreadHistoryStore restartedStore = new FileSystemThreadHistoryStore(restartedConversationStore, tempDir);
        List<ThreadHistoryItem> restored = restartedStore.read(threadId);

        assertEquals(replacement, restored);
        assertTrue(restored.get(0) instanceof CompactedHistoryItem);
        assertEquals("current", ((HistoryMessageItem) restored.get(1)).text());
        assertThrows(IllegalArgumentException.class, () -> restartedStore.read(new ThreadId("missing")));
    }
}
