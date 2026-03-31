package org.dean.codex.runtime.springai.context;

import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.core.conversation.InMemoryConversationStore;
import org.dean.codex.runtime.springai.conversation.FileSystemConversationStore;
import org.dean.codex.protocol.conversation.ItemId;
import org.dean.codex.protocol.context.ThreadMemory;
import org.dean.codex.protocol.context.ReconstructedThreadContext;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.history.CompactedHistoryItem;
import org.dean.codex.protocol.history.HistoryCompactionSummaryItem;
import org.dean.codex.protocol.history.HistoryMessageItem;
import org.dean.codex.protocol.history.HistoryToolCallItem;
import org.dean.codex.protocol.history.ThreadHistoryItem;
import org.dean.codex.protocol.item.ApprovalItem;
import org.dean.codex.protocol.item.ApprovalState;
import org.dean.codex.protocol.item.AgentMessageItem;
import org.dean.codex.protocol.item.ToolCallItem;
import org.dean.codex.protocol.item.UserMessageItem;
import org.dean.codex.runtime.springai.history.InMemoryThreadHistoryStore;
import org.dean.codex.runtime.springai.history.FileSystemThreadHistoryStore;
import org.dean.codex.runtime.springai.history.ThreadHistoryReplay;
import org.dean.codex.runtime.springai.history.ThreadHistoryMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemContextManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void compactsCanonicalHistoryIntoCompactionItemAndPreservesRecentVisibleHistory() {
        ConversationStore conversationStore = new InMemoryConversationStore();
        InMemoryThreadHistoryStore historyStore = new InMemoryThreadHistoryStore();
        ThreadId threadId = conversationStore.createThread("Compaction thread");

        Instant base = Instant.parse("2026-03-25T00:00:00Z");

        TurnId firstTurn = conversationStore.startTurn(threadId, "Inspect repo", base);
        UserMessageItem firstUser = new UserMessageItem(new ItemId("item-1"), "Inspect repo", base.plusSeconds(1));
        ToolCallItem firstToolCall = new ToolCallItem(new ItemId("item-2"), "READ_FILE", "README.md", base.plusSeconds(2));
        AgentMessageItem firstAssistant = new AgentMessageItem(new ItemId("item-3"), "Repo inspected", base.plusSeconds(3));
        conversationStore.appendTurnItems(threadId, firstTurn, List.of(firstUser, firstToolCall, firstAssistant));
        historyStore.append(threadId, ThreadHistoryMapper.map(List.of(firstUser, firstToolCall, firstAssistant)));
        conversationStore.completeTurn(threadId, firstTurn, TurnStatus.COMPLETED, "Repo inspected", base.plusSeconds(4));

        TurnId secondTurn = conversationStore.startTurn(threadId, "Run tests", base.plusSeconds(5));
        UserMessageItem secondUser = new UserMessageItem(new ItemId("item-4"), "Run tests", base.plusSeconds(6));
        ToolCallItem secondToolCall = new ToolCallItem(new ItemId("item-5"), "RUN_COMMAND", "mvn test", base.plusSeconds(7));
        AgentMessageItem secondAssistant = new AgentMessageItem(new ItemId("item-6"), "Tests passed", base.plusSeconds(8));
        conversationStore.appendTurnItems(threadId, secondTurn, List.of(secondUser, secondToolCall, secondAssistant));
        historyStore.append(threadId, ThreadHistoryMapper.map(List.of(secondUser, secondToolCall, secondAssistant)));
        conversationStore.completeTurn(threadId, secondTurn, TurnStatus.COMPLETED, "Tests passed", base.plusSeconds(9));

        TurnId thirdTurn = conversationStore.startTurn(threadId, "Write summary", base.plusSeconds(10));
        UserMessageItem thirdUser = new UserMessageItem(new ItemId("item-7"), "Write summary", base.plusSeconds(11));
        AgentMessageItem thirdAssistant = new AgentMessageItem(new ItemId("item-8"), "Summary written", base.plusSeconds(12));
        conversationStore.appendTurnItems(threadId, thirdTurn, List.of(thirdUser, thirdAssistant));
        historyStore.append(threadId, ThreadHistoryMapper.map(List.of(thirdUser, thirdAssistant)));
        conversationStore.completeTurn(threadId, thirdTurn, TurnStatus.COMPLETED, "Summary written", base.plusSeconds(13));

        FileSystemContextManager contextManager = new FileSystemContextManager(
                conversationStore,
                historyStore,
                new FixedCompactionSummarizer("Compacted summary: keep the README change and test results"),
                tempDir,
                1);

        ThreadMemory threadMemory = contextManager.compactThread(threadId);

        assertEquals(2, threadMemory.compactedTurnCount());
        assertEquals(List.of(firstTurn, secondTurn), threadMemory.sourceTurnIds());
        assertEquals("Compacted summary: keep the README change and test results", threadMemory.summary());
        assertTrue(contextManager.latestThreadMemory(threadId).isPresent());
        assertEquals(threadMemory.memoryId(), contextManager.latestThreadMemory(threadId).orElseThrow().memoryId());

        List<ThreadHistoryItem> rawHistory = historyStore.read(threadId);
        CompactedHistoryItem compacted = assertInstanceOf(CompactedHistoryItem.class, rawHistory.get(rawHistory.size() - 1));
        assertEquals("Compacted summary: keep the README change and test results", compacted.summaryText());
        assertEquals(3, compacted.replacementHistory().size());
        HistoryCompactionSummaryItem summaryItem = assertInstanceOf(HistoryCompactionSummaryItem.class, compacted.replacementHistory().get(0));
        assertEquals(thirdTurn, summaryItem.anchorTurnId());
        assertEquals("Compacted summary: keep the README change and test results", summaryItem.summaryText());
        assertInstanceOf(HistoryMessageItem.class, compacted.replacementHistory().get(1));
        assertInstanceOf(HistoryMessageItem.class, compacted.replacementHistory().get(2));
        assertFalse(compacted.replacementHistory().stream().anyMatch(item -> item instanceof HistoryToolCallItem));
    }

    @Test
    void compactionBootstrapsReplacementHistoryWhenCanonicalHistoryIsMissing() {
        ConversationStore conversationStore = new InMemoryConversationStore();
        InMemoryThreadHistoryStore historyStore = new InMemoryThreadHistoryStore();
        ThreadId threadId = conversationStore.createThread("Legacy compaction thread");

        Instant base = Instant.parse("2026-03-26T00:00:00Z");

        TurnId firstTurn = conversationStore.startTurn(threadId, "Inspect repo", base);
        conversationStore.completeTurn(threadId, firstTurn, TurnStatus.COMPLETED, "Repo inspected", base.plusSeconds(5));

        TurnId secondTurn = conversationStore.startTurn(threadId, "Run tests", base.plusSeconds(10));
        conversationStore.completeTurn(threadId, secondTurn, TurnStatus.COMPLETED, "Tests passed", base.plusSeconds(15));

        FileSystemContextManager contextManager = new FileSystemContextManager(
                conversationStore,
                historyStore,
                new FixedCompactionSummarizer("Compacted summary: inspected the repo"),
                tempDir,
                1);

        ThreadMemory threadMemory = contextManager.compactThread(threadId);

        assertEquals(List.of(firstTurn), threadMemory.sourceTurnIds());
        CompactedHistoryItem compacted = assertInstanceOf(
                CompactedHistoryItem.class,
                historyStore.read(threadId).get(historyStore.read(threadId).size() - 1));
        assertEquals(3, compacted.replacementHistory().size());
        assertInstanceOf(HistoryCompactionSummaryItem.class, compacted.replacementHistory().get(0));
        HistoryMessageItem retainedUser = assertInstanceOf(HistoryMessageItem.class, compacted.replacementHistory().get(1));
        HistoryMessageItem retainedAssistant = assertInstanceOf(HistoryMessageItem.class, compacted.replacementHistory().get(2));
        assertEquals("Run tests", retainedUser.text());
        assertEquals("Tests passed", retainedAssistant.text());
    }

    @Test
    void compactionPreservesAwaitingApprovalTurnsAcrossRestart() {
        FileSystemConversationStore conversationStore = new FileSystemConversationStore(tempDir);
        FileSystemThreadHistoryStore historyStore = new FileSystemThreadHistoryStore(conversationStore, tempDir);
        ThreadId threadId = conversationStore.createThread("Restart-safe compaction thread");

        Instant base = Instant.parse("2026-03-27T00:00:00Z");

        TurnId firstTurn = conversationStore.startTurn(threadId, "Inspect repo", base);
        UserMessageItem firstUser = new UserMessageItem(new ItemId("item-1"), "Inspect repo", base.plusSeconds(1));
        AgentMessageItem firstAssistant = new AgentMessageItem(new ItemId("item-2"), "Repo inspected", base.plusSeconds(2));
        conversationStore.appendTurnItems(threadId, firstTurn, List.of(firstUser, firstAssistant));
        historyStore.append(threadId, ThreadHistoryMapper.map(firstTurn, List.of(firstUser, firstAssistant)));
        conversationStore.completeTurn(threadId, firstTurn, TurnStatus.COMPLETED, "Repo inspected", base.plusSeconds(3));

        TurnId secondTurn = conversationStore.startTurn(threadId, "Run tests", base.plusSeconds(4));
        UserMessageItem secondUser = new UserMessageItem(new ItemId("item-3"), "Run tests", base.plusSeconds(5));
        AgentMessageItem secondAssistant = new AgentMessageItem(new ItemId("item-4"), "Tests passed", base.plusSeconds(6));
        conversationStore.appendTurnItems(threadId, secondTurn, List.of(secondUser, secondAssistant));
        historyStore.append(threadId, ThreadHistoryMapper.map(secondTurn, List.of(secondUser, secondAssistant)));
        conversationStore.completeTurn(threadId, secondTurn, TurnStatus.COMPLETED, "Tests passed", base.plusSeconds(7));

        TurnId thirdTurn = conversationStore.startTurn(threadId, "Approve command", base.plusSeconds(8));
        ApprovalItem approvalItem = new ApprovalItem(
                new ItemId("item-5"),
                ApprovalState.REQUIRED,
                "approval-1",
                "mvn test",
                "Needs approval",
                base.plusSeconds(9));
        conversationStore.appendTurnItems(threadId, thirdTurn, List.of(approvalItem));
        historyStore.append(threadId, ThreadHistoryMapper.map(thirdTurn, List.of(approvalItem)));
        conversationStore.updateTurnStatus(threadId, thirdTurn, TurnStatus.AWAITING_APPROVAL, base.plusSeconds(10));

        FileSystemContextManager contextManager = new FileSystemContextManager(
                conversationStore,
                historyStore,
                new FixedCompactionSummarizer("Compacted summary: keep the README change and test results"),
                tempDir,
                1);

        ThreadMemory threadMemory = contextManager.compactThread(threadId);
        assertEquals(List.of(firstTurn), threadMemory.sourceTurnIds());

        ConversationStore restartedConversationStore = new FileSystemConversationStore(tempDir);
        FileSystemThreadHistoryStore restartedHistoryStore = new FileSystemThreadHistoryStore(restartedConversationStore, tempDir);
        FileSystemContextManager restartedContextManager = new FileSystemContextManager(
                restartedConversationStore,
                restartedHistoryStore,
                new FixedCompactionSummarizer("Compacted summary: keep the README change and test results"),
                tempDir,
                1);
        DefaultThreadContextReconstructionService reconstructionService = new DefaultThreadContextReconstructionService(
                restartedConversationStore,
                restartedHistoryStore,
                restartedContextManager,
                2);

        ReconstructedThreadContext reconstructed = reconstructionService.reconstruct(threadId);
        assertEquals(List.of(secondTurn, thirdTurn), reconstructed.recentTurns().stream().map(turn -> turn.turnId()).toList());
        assertTrue(reconstructed.recentActivities().stream()
                .anyMatch(activity -> activity.turnId().equals(thirdTurn) && activity.detail().contains("Needs approval")));
        assertTrue(reconstructed.recentMessages().stream()
                .anyMatch(message -> message.content().contains("Compacted summary: keep the README change and test results")));
    }

    @Test
    void repeatedCompactionDoesNotDuplicatePriorSummaryItems() {
        FileSystemConversationStore conversationStore = new FileSystemConversationStore(tempDir);
        FileSystemThreadHistoryStore historyStore = new FileSystemThreadHistoryStore(conversationStore, tempDir);
        ThreadId threadId = conversationStore.createThread("Repeat compaction thread");

        Instant base = Instant.parse("2026-03-28T00:00:00Z");
        TurnId firstTurn = completedTurn(conversationStore, historyStore, threadId, "Inspect repo", "Repo inspected", base, "turn-1");
        TurnId secondTurn = completedTurn(conversationStore, historyStore, threadId, "Run tests", "Tests passed", base.plusSeconds(10), "turn-2");
        TurnId thirdTurn = completedTurn(conversationStore, historyStore, threadId, "Write summary", "Summary written", base.plusSeconds(20), "turn-3");

        FileSystemContextManager contextManager = new FileSystemContextManager(
                conversationStore,
                historyStore,
                new FixedCompactionSummarizer("Compacted summary: first pass"),
                tempDir,
                1);

        contextManager.compactThread(threadId);

        TurnId fourthTurn = completedTurn(conversationStore, historyStore, threadId, "Polish docs", "Docs polished", base.plusSeconds(30), "turn-4");

        contextManager = new FileSystemContextManager(
                conversationStore,
                historyStore,
                new FixedCompactionSummarizer("Compacted summary: second pass"),
                tempDir,
                1);
        contextManager.compactThread(threadId);

        List<ThreadHistoryItem> replayedHistory = ThreadHistoryReplay.replayVisibleHistory(historyStore.read(threadId));
        long summaryCount = replayedHistory.stream().filter(HistoryCompactionSummaryItem.class::isInstance).count();
        assertEquals(1, summaryCount);
        assertTrue(replayedHistory.stream()
                .filter(HistoryCompactionSummaryItem.class::isInstance)
                .map(HistoryCompactionSummaryItem.class::cast)
                .allMatch(summary -> summary.summaryText().equals("Compacted summary: second pass")));
        assertTrue(replayedHistory.stream().anyMatch(item -> item.turnId().equals(fourthTurn)));
    }

    private TurnId completedTurn(ConversationStore conversationStore,
                                 FileSystemThreadHistoryStore historyStore,
                                 ThreadId threadId,
                                 String input,
                                 String output,
                                 Instant startedAt,
                                 String turnIdValue) {
        TurnId turnId = conversationStore.startTurn(threadId, input, startedAt);
        UserMessageItem userMessageItem = new UserMessageItem(new ItemId(turnIdValue + "-user"), input, startedAt.plusSeconds(1));
        AgentMessageItem assistantMessageItem = new AgentMessageItem(new ItemId(turnIdValue + "-assistant"), output, startedAt.plusSeconds(2));
        conversationStore.appendTurnItems(threadId, turnId, List.of(userMessageItem, assistantMessageItem));
        historyStore.append(threadId, ThreadHistoryMapper.map(turnId, List.of(userMessageItem, assistantMessageItem)));
        conversationStore.completeTurn(threadId, turnId, TurnStatus.COMPLETED, output, startedAt.plusSeconds(3));
        return turnId;
    }

    private static final class FixedCompactionSummarizer implements ThreadCompactionSummarizer {

        private final String summary;

        private FixedCompactionSummarizer(String summary) {
            this.summary = summary;
        }

        @Override
        public String summarize(ThreadId threadId, List<ThreadHistoryItem> compactedHistory, List<ThreadHistoryItem> retainedHistory) {
            return summary;
        }
    }
}
