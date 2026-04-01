package org.dean.codex.runtime.springai.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.protocol.agent.AgentStatus;
import org.dean.codex.protocol.appserver.ThreadForkParams;
import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.ItemId;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.ThreadSource;
import org.dean.codex.protocol.conversation.ThreadStatus;
import org.dean.codex.protocol.conversation.ThreadSummary;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.item.ToolCallItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemConversationStoreTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

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
        assertEquals("hello", secondStore.listThreads().get(0).preview());
        assertEquals(ThreadStatus.NOT_LOADED, secondStore.listThreads().get(0).status());
        assertEquals(ThreadSource.UNKNOWN, secondStore.listThreads().get(0).source());
        assertTrue(secondStore.listThreads().get(0).materialized());
        assertNotNull(secondStore.listThreads().get(0).path());
        assertEquals(1, secondStore.turns(threadId).size());
        assertEquals("hello", secondStore.turns(threadId).get(0).userInput());
        assertEquals("done", secondStore.turns(threadId).get(0).finalAnswer());
        assertEquals(1, secondStore.turns(threadId).get(0).items().size());
    }

    @Test
    void readsLegacyThreadMetadataWithRicherDefaults() throws Exception {
        ThreadId threadId = new ThreadId("thread-legacy");
        Path threadDirectory = tempDir.resolve("threads").resolve(threadId.value());
        Files.createDirectories(threadDirectory.resolve("turns"));
        Files.writeString(threadDirectory.resolve("thread.json"), """
                {
                  "threadId": "thread-legacy",
                  "title": "Legacy thread",
                  "createdAt": "2026-03-25T00:00:00Z",
                  "updatedAt": "2026-03-25T00:00:00Z"
                }
                """);
        ConversationTurn turn = new ConversationTurn(
                new TurnId("turn-legacy"),
                threadId,
                "Legacy preview should come from the first message",
                "done",
                TurnStatus.COMPLETED,
                Instant.parse("2026-03-25T00:00:00Z"),
                Instant.parse("2026-03-25T00:01:00Z"),
                List.of(),
                List.of());
        objectMapper.writeValue(threadDirectory.resolve("turns").resolve("turn-legacy.json").toFile(), turn);

        ConversationStore store = new FileSystemConversationStore(tempDir);
        var summary = store.listThreads().get(0);

        assertEquals(threadId, summary.threadId());
        assertEquals("Legacy preview should come from the first message", summary.preview());
        assertEquals(ThreadStatus.NOT_LOADED, summary.status());
        assertEquals(ThreadSource.UNKNOWN, summary.source());
        assertTrue(summary.materialized());
    }

    @Test
    void forksThreadsWithCopiedTurnsAndPersistedOverrides() {
        ConversationStore firstStore = new FileSystemConversationStore(tempDir);
        ThreadId parentThreadId = firstStore.createThread("Parent thread");
        Instant startedAt = Instant.parse("2026-04-01T00:00:00Z");
        TurnId parentTurnId = firstStore.startTurn(parentThreadId, "inspect repo", startedAt);
        firstStore.updateTurnStatus(parentThreadId, parentTurnId, TurnStatus.RUNNING, startedAt.plusSeconds(1));

        ThreadId forkedThreadId = firstStore.forkThread(new ThreadForkParams(
                parentThreadId,
                "Forked thread",
                Boolean.TRUE,
                "/workspace/forked",
                "openai",
                "gpt-5.4",
                ThreadSource.SUB_AGENT,
                "worker-1",
                "reviewer",
                "root/worker-1"));

        ConversationStore restartedStore = new FileSystemConversationStore(tempDir);
        var forkedSummary = restartedStore.listThreads().stream()
                .filter(summary -> summary.threadId().equals(forkedThreadId))
                .findFirst()
                .orElseThrow();

        assertEquals("Forked thread", forkedSummary.title());
        assertTrue(forkedSummary.ephemeral());
        assertEquals("/workspace/forked", forkedSummary.cwd());
        assertEquals("openai", forkedSummary.modelProvider());
        assertEquals("gpt-5.4", forkedSummary.model());
        assertEquals(ThreadSource.SUB_AGENT, forkedSummary.source());
        assertEquals("worker-1", forkedSummary.agentNickname());
        assertEquals("reviewer", forkedSummary.agentRole());
        assertEquals("root/worker-1", forkedSummary.agentPath());
        assertEquals(1, restartedStore.turns(parentThreadId).size());
        assertEquals(1, restartedStore.turns(forkedThreadId).size());
        assertEquals(forkedThreadId, restartedStore.turns(forkedThreadId).get(0).threadId());
        assertEquals(TurnStatus.INTERRUPTED, restartedStore.turns(forkedThreadId).get(0).status());
        assertNotNull(restartedStore.turns(forkedThreadId).get(0).completedAt());

        TurnId forkedTurnId = restartedStore.startTurn(forkedThreadId, "follow up", Instant.parse("2026-04-01T00:00:01Z"));
        restartedStore.completeTurn(forkedThreadId, forkedTurnId, TurnStatus.COMPLETED, "child done", Instant.parse("2026-04-01T00:00:02Z"));

        ConversationStore restartedAgain = new FileSystemConversationStore(tempDir);
        assertEquals(1, restartedAgain.turns(parentThreadId).size());
        assertEquals(2, restartedAgain.turns(forkedThreadId).size());
        assertEquals("child done", restartedAgain.turns(forkedThreadId).get(1).finalAnswer());
    }

    @Test
    void archivesUnarchivesAndRollsBackAcrossStoreInstances() {
        ConversationStore firstStore = new FileSystemConversationStore(tempDir);
        ThreadId threadId = firstStore.createThread("Lifecycle thread");
        Instant base = Instant.parse("2026-04-01T00:00:00Z");
        TurnId firstTurn = firstStore.startTurn(threadId, "inspect repo", base);
        firstStore.completeTurn(threadId, firstTurn, TurnStatus.COMPLETED, "inspected", base.plusSeconds(1));
        TurnId secondTurn = firstStore.startTurn(threadId, "run tests", base.plusSeconds(2));
        firstStore.completeTurn(threadId, secondTurn, TurnStatus.COMPLETED, "tested", base.plusSeconds(3));
        TurnId thirdTurn = firstStore.startTurn(threadId, "write summary", base.plusSeconds(4));
        firstStore.completeTurn(threadId, thirdTurn, TurnStatus.COMPLETED, "summarized", base.plusSeconds(5));

        ThreadSummary archived = firstStore.archiveThread(threadId);
        assertTrue(archived.archived());

        ConversationStore reopenedStore = new FileSystemConversationStore(tempDir);
        ThreadSummary reopenedArchived = reopenedStore.listThreads().stream()
                .filter(summary -> summary.threadId().equals(threadId))
                .findFirst()
                .orElseThrow();
        assertTrue(reopenedArchived.archived());
        assertNotNull(reopenedArchived.archivedAt());

        ThreadSummary unarchived = reopenedStore.unarchiveThread(threadId);
        assertFalse(unarchived.archived());

        ConversationStore reopenedAgain = new FileSystemConversationStore(tempDir);
        ThreadSummary unarchivedSummary = reopenedAgain.listThreads().stream()
                .filter(summary -> summary.threadId().equals(threadId))
                .findFirst()
                .orElseThrow();
        assertFalse(unarchivedSummary.archived());
        assertEquals(3, unarchivedSummary.turnCount());

        ThreadSummary rolledBack = reopenedAgain.rollbackThread(threadId, 2);
        assertEquals(1, rolledBack.turnCount());
        assertEquals("inspect repo", rolledBack.preview());

        ConversationStore reopenedAfterRollback = new FileSystemConversationStore(tempDir);
        ThreadSummary finalSummary = reopenedAfterRollback.listThreads().stream()
                .filter(summary -> summary.threadId().equals(threadId))
                .findFirst()
                .orElseThrow();
        assertEquals(1, finalSummary.turnCount());
        assertEquals("inspect repo", finalSummary.preview());
        assertEquals(1, reopenedAfterRollback.turns(threadId).size());
        assertEquals("inspected", reopenedAfterRollback.turns(threadId).get(0).finalAnswer());
    }

    @Test
    void persistsAgentRelationshipFieldsAcrossStoreInstances() {
        ConversationStore firstStore = new FileSystemConversationStore(tempDir);
        ThreadId parentThreadId = firstStore.createThread("Parent thread");
        ThreadId childThreadId = firstStore.createThread("Child thread");

        firstStore.updateAgentThread(
                childThreadId,
                parentThreadId,
                1,
                Instant.parse("2026-04-01T00:00:00Z"),
                "worker-1",
                "worker",
                "root/worker-1");

        ConversationStore restartedStore = new FileSystemConversationStore(tempDir);
        ThreadSummary childSummary = restartedStore.listThreads().stream()
                .filter(summary -> summary.threadId().equals(childThreadId))
                .findFirst()
                .orElseThrow();

        assertEquals(parentThreadId, childSummary.parentThreadId());
        assertEquals(1, childSummary.agentDepth());
        assertEquals(AgentStatus.SHUTDOWN, childSummary.agentStatus());
        assertEquals("worker-1", childSummary.agentNickname());
        assertEquals("root/worker-1", childSummary.agentPath());
    }
}
