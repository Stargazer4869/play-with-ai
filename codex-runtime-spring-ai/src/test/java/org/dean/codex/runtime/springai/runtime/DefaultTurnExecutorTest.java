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
import org.dean.codex.protocol.history.HistoryApprovalItem;
import org.dean.codex.protocol.history.HistoryMessageItem;
import org.dean.codex.protocol.history.HistoryPlanItem;
import org.dean.codex.protocol.history.HistorySkillUseItem;
import org.dean.codex.protocol.history.HistoryToolCallItem;
import org.dean.codex.protocol.history.HistoryToolResultItem;
import org.dean.codex.protocol.history.ThreadHistoryItem;
import org.dean.codex.protocol.item.AgentMessageItem;
import org.dean.codex.protocol.item.ApprovalItem;
import org.dean.codex.protocol.item.ApprovalState;
import org.dean.codex.protocol.item.PlanItem;
import org.dean.codex.protocol.item.SkillUseItem;
import org.dean.codex.protocol.item.ToolCallItem;
import org.dean.codex.protocol.item.ToolResultItem;
import org.dean.codex.protocol.item.TurnItem;
import org.dean.codex.protocol.item.UserMessageItem;
import org.dean.codex.protocol.planning.EditPlan;
import org.dean.codex.protocol.planning.PlannedEdit;
import org.dean.codex.protocol.planning.PlannedEditType;
import org.dean.codex.protocol.skill.SkillMetadata;
import org.dean.codex.protocol.skill.SkillScope;
import org.dean.codex.runtime.springai.history.InMemoryThreadHistoryStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultTurnExecutorTest {

    @Test
    void recordsCanonicalHistoryInPromptOrderForNormalTurns() {
        ConversationStore conversationStore = new InMemoryConversationStore();
        InMemoryThreadHistoryStore historyStore = new InMemoryThreadHistoryStore();
        ThreadId threadId = conversationStore.createThread("Executor thread");
        DefaultTurnExecutor executor = new DefaultTurnExecutor(new RecordingCodexAgent(), conversationStore, historyStore);

        CodexTurnResult result = executor.executeTurn(threadId, "inspect repo");

        assertEquals(TurnStatus.COMPLETED, result.status());
        assertEquals(1, conversationStore.turns(threadId).size());
        assertEquals(7, conversationStore.turns(threadId).get(0).items().size());

        List<ThreadHistoryItem> history = historyStore.read(threadId);
        assertEquals(7, history.size());
        assertInstanceOf(HistoryMessageItem.class, history.get(0));
        assertInstanceOf(HistorySkillUseItem.class, history.get(1));
        assertInstanceOf(HistoryMessageItem.class, history.get(2));
        assertInstanceOf(HistoryPlanItem.class, history.get(3));
        assertInstanceOf(HistoryToolCallItem.class, history.get(4));
        assertInstanceOf(HistoryToolResultItem.class, history.get(5));
        assertInstanceOf(HistoryMessageItem.class, history.get(6));

        assertEquals("inspect repo", ((HistoryMessageItem) history.get(0)).text());
        assertEquals("doc-skill", ((HistorySkillUseItem) history.get(1)).skills().get(0).name());
        assertEquals("Steer toward README.md", ((HistoryMessageItem) history.get(2)).text());
        assertEquals("done", ((HistoryMessageItem) history.get(6)).text());
    }

    @Test
    void recordsCanonicalHistoryAcrossApprovalPauseAndResume() {
        ConversationStore conversationStore = new InMemoryConversationStore();
        InMemoryThreadHistoryStore historyStore = new InMemoryThreadHistoryStore();
        ThreadId threadId = conversationStore.createThread("Executor thread");
        DefaultTurnExecutor executor = new DefaultTurnExecutor(new PausingCodexAgent(), conversationStore, historyStore);

        CodexTurnResult paused = executor.executeTurn(threadId, "run tests");

        assertEquals(TurnStatus.AWAITING_APPROVAL, paused.status());
        assertEquals(2, historyStore.read(threadId).size());
        assertInstanceOf(HistoryMessageItem.class, historyStore.read(threadId).get(0));
        assertInstanceOf(HistoryApprovalItem.class, historyStore.read(threadId).get(1));

        CodexTurnResult resumed = executor.resumeTurn(threadId, paused.turnId());

        assertEquals(TurnStatus.COMPLETED, resumed.status());
        List<ThreadHistoryItem> history = historyStore.read(threadId);
        assertEquals(4, history.size());
        assertInstanceOf(HistoryMessageItem.class, history.get(0));
        assertInstanceOf(HistoryApprovalItem.class, history.get(1));
        assertInstanceOf(HistoryToolResultItem.class, history.get(2));
        assertInstanceOf(HistoryMessageItem.class, history.get(3));
        assertEquals("run tests", ((HistoryMessageItem) history.get(0)).text());
        assertEquals("Approval required", ((HistoryApprovalItem) history.get(1)).detail());
        assertEquals("success=true", ((HistoryToolResultItem) history.get(2)).summary());
        assertEquals("done after approval", ((HistoryMessageItem) history.get(3)).text());
    }

    @Test
    void persistsInterruptedTurnsAsTerminalState() {
        ConversationStore store = new InMemoryConversationStore();
        InMemoryThreadHistoryStore historyStore = new InMemoryThreadHistoryStore();
        ThreadId threadId = store.createThread("Executor thread");
        DefaultTurnExecutor executor = new DefaultTurnExecutor(new InterruptibleCodexAgent(), store, historyStore);
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
        assertEquals(1, historyStore.read(threadId).size());
        assertInstanceOf(HistoryMessageItem.class, historyStore.read(threadId).get(0));
    }

    private static final class RecordingCodexAgent implements CodexAgent {

        @Override
        public CodexTurnResult handleTurn(ThreadId threadId,
                                          TurnId turnId,
                                          String input,
                                          Consumer<TurnItem> eventConsumer) {
            List<TurnItem> items = new ArrayList<>();
            SkillUseItem skillUseItem = new SkillUseItem(
                    new ItemId("event-1"),
                    List.of(new SkillMetadata("doc-skill", "Read docs", "Read docs", "skills/doc-skill", SkillScope.WORKSPACE, true)),
                    Instant.parse("2026-03-31T00:00:01Z"));
            UserMessageItem steeringMessage = new UserMessageItem(new ItemId("event-2"), "Steer toward README.md", Instant.parse("2026-03-31T00:00:02Z"));
            PlanItem planItem = new PlanItem(
                    new ItemId("event-3"),
                    new EditPlan(
                            "Update README",
                            List.of(new PlannedEdit("README.md", PlannedEditType.MODIFY, "Clarify setup"))),
                    Instant.parse("2026-03-31T00:00:03Z"));
            ToolCallItem toolCallItem = new ToolCallItem(new ItemId("event-4"), "READ_FILE", "README.md", Instant.parse("2026-03-31T00:00:04Z"));
            ToolResultItem toolResultItem = new ToolResultItem(new ItemId("event-5"), "READ_FILE", "success=true", Instant.parse("2026-03-31T00:00:05Z"));
            AgentMessageItem assistantMessageItem = new AgentMessageItem(new ItemId("event-6"), "done", Instant.parse("2026-03-31T00:00:06Z"));
            for (TurnItem item : List.of(skillUseItem, steeringMessage, planItem, toolCallItem, toolResultItem, assistantMessageItem)) {
                items.add(item);
                eventConsumer.accept(item);
            }
            return new CodexTurnResult(threadId, turnId, TurnStatus.COMPLETED, items, "done");
        }

        @Override
        public CodexTurnResult handleTurn(ThreadId threadId, TurnId turnId, String input) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class PausingCodexAgent implements CodexAgent {

        private final AtomicInteger invocationCount = new AtomicInteger();

        @Override
        public CodexTurnResult handleTurn(ThreadId threadId,
                                          TurnId turnId,
                                          String input,
                                          Consumer<TurnItem> eventConsumer) {
            if (invocationCount.incrementAndGet() == 1) {
                ApprovalItem approvalItem = new ApprovalItem(
                        new ItemId("event-1"),
                        ApprovalState.REQUIRED,
                        "abc123",
                        "mvn test",
                        "Approval required",
                        Instant.parse("2026-03-31T00:00:10Z"));
                eventConsumer.accept(approvalItem);
                return new CodexTurnResult(
                        threadId,
                        turnId,
                        TurnStatus.AWAITING_APPROVAL,
                        List.of(approvalItem),
                        "Approval required");
            }
            ToolResultItem toolResultItem = new ToolResultItem(
                    new ItemId("event-2"),
                    "RUN_COMMAND",
                    "success=true",
                    Instant.parse("2026-03-31T00:00:11Z"));
            AgentMessageItem assistantMessageItem = new AgentMessageItem(
                    new ItemId("event-3"),
                    "done after approval",
                    Instant.parse("2026-03-31T00:00:12Z"));
            eventConsumer.accept(toolResultItem);
            eventConsumer.accept(assistantMessageItem);
            return new CodexTurnResult(
                    threadId,
                    turnId,
                    TurnStatus.COMPLETED,
                    List.of(toolResultItem, assistantMessageItem),
                    "done after approval");
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
