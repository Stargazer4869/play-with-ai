package org.dean.codex.runtime.springai.context;

import org.dean.codex.core.context.ContextManager;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.core.conversation.InMemoryConversationStore;
import org.dean.codex.protocol.context.ReconstructedThreadContext;
import org.dean.codex.protocol.context.ThreadMemory;
import org.dean.codex.protocol.conversation.ItemId;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.item.ToolCallItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultThreadContextReconstructionServiceTest {

    @Test
    void reconstructUsesThreadMemoryAndExcludesCompactedTurnsFromRecentContext() {
        ConversationStore store = new InMemoryConversationStore();
        ThreadId threadId = store.createThread("Reconstruction thread");

        Instant now = Instant.parse("2026-03-30T10:15:30Z");
        TurnId firstTurn = store.startTurn(threadId, "Inspect repo", now);
        store.completeTurn(threadId, firstTurn, TurnStatus.COMPLETED, "Inspected repo", now.plusSeconds(1));

        TurnId secondTurn = store.startTurn(threadId, "Update docs", now.plusSeconds(2));
        store.appendTurnItems(threadId, secondTurn, List.of(
                new ToolCallItem(new ItemId("item-1"), "READ_FILE", "README.md", now.plusSeconds(3))));
        store.completeTurn(threadId, secondTurn, TurnStatus.COMPLETED, "Updated docs", now.plusSeconds(4));

        TurnId thirdTurn = store.startTurn(threadId, "Run tests", now.plusSeconds(5));
        store.completeTurn(threadId, thirdTurn, TurnStatus.COMPLETED, "Ran tests", now.plusSeconds(6));

        ThreadMemory threadMemory = new ThreadMemory(
                "memory-1",
                threadId,
                "Compacted earlier thread context.",
                List.of(firstTurn),
                1,
                now.plusSeconds(7));
        DefaultThreadContextReconstructionService service = new DefaultThreadContextReconstructionService(
                store,
                new FixedContextManager(threadMemory),
                2);

        ReconstructedThreadContext reconstructed = service.reconstruct(threadId);

        assertEquals("memory-1", reconstructed.threadMemory().memoryId());
        assertEquals(List.of(secondTurn, thirdTurn), reconstructed.recentTurns().stream().map(turn -> turn.turnId()).toList());
        assertEquals(List.of(secondTurn, secondTurn, thirdTurn, thirdTurn),
                reconstructed.recentMessages().stream().map(message -> message.turnId()).toList());
        assertTrue(reconstructed.recentActivities().stream()
                .anyMatch(activity -> activity.turnId().equals(secondTurn) && activity.detail().contains("READ_FILE")));
    }

    private static final class FixedContextManager implements ContextManager {

        private final ThreadMemory threadMemory;

        private FixedContextManager(ThreadMemory threadMemory) {
            this.threadMemory = threadMemory;
        }

        @Override
        public Optional<ThreadMemory> latestThreadMemory(ThreadId threadId) {
            return Optional.of(threadMemory);
        }

        @Override
        public ThreadMemory compactThread(ThreadId threadId) {
            return threadMemory;
        }
    }
}
