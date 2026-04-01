package org.dean.codex.runtime.springai.runtime;

import org.dean.codex.core.agent.TurnExecutor;
import org.dean.codex.core.agent.TurnControl;
import org.dean.codex.core.context.ContextManager;
import org.dean.codex.core.context.ThreadContextReconstructionService;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.core.conversation.InMemoryConversationStore;
import org.dean.codex.core.history.ThreadHistoryStore;
import org.dean.codex.core.runtime.CodexRuntimeGateway;
import org.dean.codex.protocol.agent.AgentMessage;
import org.dean.codex.protocol.agent.AgentSpawnRequest;
import org.dean.codex.protocol.agent.AgentStatus;
import org.dean.codex.core.skill.ResolvedSkill;
import org.dean.codex.core.skill.SkillService;
import org.dean.codex.protocol.appserver.ThreadForkParams;
import org.dean.codex.protocol.context.ReconstructedThreadContext;
import org.dean.codex.protocol.context.ThreadMemory;
import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.ItemId;
import org.dean.codex.protocol.conversation.ThreadActiveFlag;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.ThreadStatus;
import org.dean.codex.protocol.conversation.ThreadSummary;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.event.CodexTurnResult;
import org.dean.codex.protocol.history.ThreadHistoryItem;
import org.dean.codex.protocol.item.ToolCallItem;
import org.dean.codex.protocol.item.AgentMessageItem;
import org.dean.codex.protocol.item.TurnItem;
import org.dean.codex.protocol.item.UserMessageItem;
import org.dean.codex.protocol.runtime.RuntimeNotification;
import org.dean.codex.protocol.runtime.RuntimeNotificationType;
import org.dean.codex.protocol.runtime.RuntimeTurn;
import org.dean.codex.protocol.skill.SkillMetadata;
import org.dean.codex.protocol.skill.SkillScope;
import org.dean.codex.runtime.springai.context.DefaultThreadContextReconstructionService;
import org.dean.codex.runtime.springai.history.InMemoryThreadHistoryStore;
import org.dean.codex.runtime.springai.history.ThreadHistoryMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultCodexRuntimeGatewayTest {

    @Test
    void threadResumePublishesThreadResumedNotification() throws Exception {
        ConversationStore store = new InMemoryConversationStore();
        ThreadId threadId = store.createThread("Gateway thread");
        CodexRuntimeGateway gateway = new DefaultCodexRuntimeGateway(store, new CompletingTurnExecutor(store), new NoOpContextManager(), new NoOpThreadContextReconstructionService(), new NoOpSkillService());
        BlockingQueue<RuntimeNotification> notifications = new LinkedBlockingQueue<>();

        try (AutoCloseable ignored = gateway.subscribe(threadId, notifications::add)) {
            gateway.threadResume(threadId);
            RuntimeNotification notification = notifications.poll(2, TimeUnit.SECONDS);
            assertNotNull(notification);
            assertEquals(RuntimeNotificationType.THREAD_RESUMED, notification.type());
            assertEquals(threadId, notification.threadId());
            assertEquals(ThreadStatus.IDLE, notification.thread().status());
            assertTrue(notification.thread().loaded());
        }
    }

    @Test
    void threadStartLoadsThreadAndReturnsIdleSummary() {
        ConversationStore store = new InMemoryConversationStore();
        CodexRuntimeGateway gateway = new DefaultCodexRuntimeGateway(store, new CompletingTurnExecutor(store), new NoOpContextManager(), new NoOpThreadContextReconstructionService(), new NoOpSkillService());

        ThreadSummary summary = gateway.threadStart("Gateway thread");

        assertEquals(ThreadStatus.IDLE, summary.status());
        assertTrue(gateway.loadedThreads().contains(summary.threadId()));
    }

    @Test
    void listThreadsLeavePersistedThreadsNotLoadedUntilResumed() {
        ConversationStore store = new InMemoryConversationStore();
        ThreadId threadId = store.createThread("Gateway thread");
        TurnId turnId = store.startTurn(threadId, "needs approval", Instant.parse("2026-04-01T00:00:00Z"));
        store.updateTurnStatus(threadId, turnId, TurnStatus.AWAITING_APPROVAL, Instant.parse("2026-04-01T00:00:01Z"));
        CodexRuntimeGateway gateway = new DefaultCodexRuntimeGateway(
                store,
                new CompletingTurnExecutor(store),
                new NoOpContextManager(),
                new NoOpThreadContextReconstructionService(),
                new NoOpSkillService());

        var summary = gateway.listThreads().get(0);

        assertEquals(ThreadStatus.NOT_LOADED, summary.status());
        assertEquals(List.of(), summary.activeFlags());
        assertFalse(summary.loaded());
    }

    @Test
    void listThreadsReflectsActiveApprovalStateForLoadedThread() {
        ConversationStore store = new InMemoryConversationStore();
        ThreadId threadId = store.createThread("Gateway thread");
        TurnId turnId = store.startTurn(threadId, "needs approval", Instant.parse("2026-04-01T00:00:00Z"));
        store.updateTurnStatus(threadId, turnId, TurnStatus.AWAITING_APPROVAL, Instant.parse("2026-04-01T00:00:01Z"));
        CodexRuntimeGateway gateway = new DefaultCodexRuntimeGateway(
                store,
                new CompletingTurnExecutor(store),
                new NoOpContextManager(),
                new NoOpThreadContextReconstructionService(),
                new NoOpSkillService());

        gateway.threadResume(threadId);
        var summary = gateway.listThreads().get(0);

        assertEquals(ThreadStatus.ACTIVE, summary.status());
        assertEquals(List.of(ThreadActiveFlag.WAITING_ON_APPROVAL), summary.activeFlags());
        assertTrue(summary.loaded());
        assertEquals(List.of(threadId), gateway.loadedThreads());
    }

    @Test
    void threadRollbackTrimsCanonicalHistoryUsedForReconstruction() {
        ConversationStore conversationStore = new InMemoryConversationStore();
        InMemoryThreadHistoryStore historyStore = new InMemoryThreadHistoryStore();
        ThreadId threadId = conversationStore.createThread("Rollback thread");
        Instant base = Instant.parse("2026-04-01T00:00:00Z");

        TurnId firstTurnId = conversationStore.startTurn(threadId, "inspect repo", base);
        UserMessageItem firstUser = new UserMessageItem(new ItemId("first-user"), "inspect repo", base.plusSeconds(1));
        AgentMessageItem firstAssistant = new AgentMessageItem(new ItemId("first-assistant"), "repo inspected", base.plusSeconds(2));
        conversationStore.appendTurnItems(threadId, firstTurnId, List.of(firstUser, firstAssistant));
        historyStore.append(threadId, ThreadHistoryMapper.map(firstTurnId, List.of(firstUser, firstAssistant)));
        conversationStore.completeTurn(threadId, firstTurnId, TurnStatus.COMPLETED, "repo inspected", base.plusSeconds(3));

        TurnId secondTurnId = conversationStore.startTurn(threadId, "run tests", base.plusSeconds(4));
        UserMessageItem secondUser = new UserMessageItem(new ItemId("second-user"), "run tests", base.plusSeconds(5));
        AgentMessageItem secondAssistant = new AgentMessageItem(new ItemId("second-assistant"), "tests ran", base.plusSeconds(6));
        conversationStore.appendTurnItems(threadId, secondTurnId, List.of(secondUser, secondAssistant));
        historyStore.append(threadId, ThreadHistoryMapper.map(secondTurnId, List.of(secondUser, secondAssistant)));
        conversationStore.completeTurn(threadId, secondTurnId, TurnStatus.COMPLETED, "tests ran", base.plusSeconds(7));

        var reconstructionService = new DefaultThreadContextReconstructionService(
                conversationStore,
                historyStore,
                new NoOpContextManager(),
                8);
        CodexRuntimeGateway gateway = new DefaultCodexRuntimeGateway(
                conversationStore,
                new CompletingTurnExecutor(conversationStore),
                new NoOpContextManager(),
                reconstructionService,
                historyStore,
                new NoOpSkillService());

        ThreadSummary rolledBack = gateway.threadRollback(threadId, 1);
        ReconstructedThreadContext reconstructed = gateway.reconstructThreadContext(threadId);

        assertEquals(1, rolledBack.turnCount());
        assertEquals(List.of(firstTurnId), reconstructed.recentTurns().stream().map(ConversationTurn::turnId).toList());
        assertEquals(List.of("inspect repo", "repo inspected"),
                reconstructed.recentMessages().stream().map(message -> message.content()).toList());
        assertEquals(2, historyStore.read(threadId).size());
        assertTrue(historyStore.read(threadId).stream().allMatch(item -> firstTurnId.equals(item.turnId())));
    }

    @Test
    void threadForkCopiesCanonicalHistoryAndDivergesOnFollowUpTurn() throws Exception {
        ConversationStore conversationStore = new InMemoryConversationStore();
        InMemoryThreadHistoryStore historyStore = new InMemoryThreadHistoryStore();
        ThreadId parentThreadId = conversationStore.createThread("Parent thread");
        Instant base = Instant.parse("2026-04-01T00:00:00Z");
        TurnId parentTurnId = conversationStore.startTurn(parentThreadId, "inspect repo", base);
        UserMessageItem parentUser = new UserMessageItem(new ItemId("parent-user"), "inspect repo", base.plusSeconds(1));
        AgentMessageItem parentAssistant = new AgentMessageItem(new ItemId("parent-assistant"), "repo inspected", base.plusSeconds(2));
        conversationStore.appendTurnItems(parentThreadId, parentTurnId, List.of(parentUser, parentAssistant));
        historyStore.append(parentThreadId, ThreadHistoryMapper.map(parentTurnId, List.of(parentUser, parentAssistant)));
        conversationStore.completeTurn(parentThreadId, parentTurnId, TurnStatus.COMPLETED, "repo inspected", base.plusSeconds(3));

        CodexRuntimeGateway gateway = new DefaultCodexRuntimeGateway(
                conversationStore,
                new ForkingTurnExecutor(conversationStore, historyStore),
                new NoOpContextManager(),
                new NoOpThreadContextReconstructionService(),
                historyStore,
                new NoOpSkillService());

        ThreadSummary forked = gateway.threadFork(new ThreadForkParams(
                parentThreadId,
                "Forked thread",
                Boolean.TRUE,
                "/workspace/forked",
                "openai",
                "gpt-5.4",
                org.dean.codex.protocol.conversation.ThreadSource.APP_SERVER,
                "worker-1",
                "worker",
                "root/worker-1"));

        assertTrue(gateway.loadedThreads().contains(forked.threadId()));
        assertEquals("Forked thread", forked.title());
        assertTrue(forked.ephemeral());
        assertEquals("/workspace/forked", forked.cwd());
        assertEquals("openai", forked.modelProvider());
        assertEquals("gpt-5.4", forked.model());
        assertEquals(org.dean.codex.protocol.conversation.ThreadSource.APP_SERVER, forked.source());
        assertEquals("worker-1", forked.agentNickname());
        assertEquals("worker", forked.agentRole());
        assertEquals("root/worker-1", forked.agentPath());

        assertEquals(1, conversationStore.turns(parentThreadId).size());
        assertEquals(1, conversationStore.turns(forked.threadId()).size());
        assertEquals(forked.threadId(), conversationStore.turns(forked.threadId()).get(0).threadId());
        assertEquals(historyStore.read(parentThreadId), historyStore.read(forked.threadId()));

        RuntimeTurn childTurn = gateway.turnStart(forked.threadId(), "write follow-up");
        awaitTurnStatus(conversationStore, forked.threadId(), childTurn.turnId(), TurnStatus.COMPLETED);
        assertEquals(2, conversationStore.turns(forked.threadId()).size());
        assertEquals(1, conversationStore.turns(parentThreadId).size());
        assertTrue(historyStore.read(forked.threadId()).size() > historyStore.read(parentThreadId).size());
    }

    @Test
    void threadForkInterruptsCopiedInFlightTurn() {
        ConversationStore conversationStore = new InMemoryConversationStore();
        ThreadId parentThreadId = conversationStore.createThread("Parent thread");
        Instant startedAt = Instant.parse("2026-04-01T00:00:00Z");
        TurnId runningTurnId = conversationStore.startTurn(parentThreadId, "long running task", startedAt);
        conversationStore.updateTurnStatus(parentThreadId, runningTurnId, TurnStatus.RUNNING, startedAt.plusSeconds(1));

        CodexRuntimeGateway gateway = new DefaultCodexRuntimeGateway(
                conversationStore,
                new CompletingTurnExecutor(conversationStore),
                new NoOpContextManager(),
                new NoOpThreadContextReconstructionService(),
                new NoOpSkillService());

        ThreadSummary forked = gateway.threadFork(new ThreadForkParams(parentThreadId));
        ConversationTurn copiedTurn = conversationStore.turns(forked.threadId()).get(0);

        assertEquals(forked.threadId(), copiedTurn.threadId());
        assertEquals(TurnStatus.INTERRUPTED, copiedTurn.status());
        assertNotNull(copiedTurn.completedAt());
        assertEquals(ThreadStatus.IDLE, gateway.listThreads().stream()
                .filter(summary -> summary.threadId().equals(forked.threadId()))
                .findFirst()
                .orElseThrow()
                .status());
    }

    @Test
    void spawnAgentPersistsParentRelationshipCopiesHistoryAndExposesMailboxWait() {
        ConversationStore conversationStore = new InMemoryConversationStore();
        InMemoryThreadHistoryStore historyStore = new InMemoryThreadHistoryStore();
        ThreadId parentThreadId = conversationStore.createThread("Parent thread");
        Instant base = Instant.parse("2026-04-01T00:00:00Z");
        TurnId parentTurnId = conversationStore.startTurn(parentThreadId, "inspect repo", base);
        UserMessageItem parentUser = new UserMessageItem(new ItemId("parent-user"), "inspect repo", base.plusSeconds(1));
        AgentMessageItem parentAssistant = new AgentMessageItem(new ItemId("parent-assistant"), "repo inspected", base.plusSeconds(2));
        conversationStore.appendTurnItems(parentThreadId, parentTurnId, List.of(parentUser, parentAssistant));
        historyStore.append(parentThreadId, ThreadHistoryMapper.map(parentTurnId, List.of(parentUser, parentAssistant)));
        conversationStore.completeTurn(parentThreadId, parentTurnId, TurnStatus.COMPLETED, "repo inspected", base.plusSeconds(3));

        DefaultCodexRuntimeGateway gateway = new DefaultCodexRuntimeGateway(
                conversationStore,
                new CompletingTurnExecutor(conversationStore),
                new NoOpContextManager(),
                new NoOpThreadContextReconstructionService(),
                historyStore,
                new NoOpSkillService(),
                4);

        var agent = gateway.spawnAgent(new AgentSpawnRequest(
                parentThreadId,
                "root/worker-1",
                "Investigate the failing tests",
                "worker-1",
                "worker",
                null,
                "openai",
                "gpt-5.4",
                "/workspace/worker"));

        assertEquals(parentThreadId, agent.parentThreadId());
        assertEquals("worker-1", agent.nickname());
        assertEquals("worker", agent.role());
        assertEquals("root/worker-1", agent.path());
        assertEquals(1, agent.depth());
        assertEquals(AgentStatus.RUNNING, agent.status());
        assertEquals(historyStore.read(parentThreadId), historyStore.read(agent.threadId()));
        assertEquals(parentThreadId, gateway.listThreads().stream()
                .filter(summary -> summary.threadId().equals(agent.threadId()))
                .findFirst()
                .orElseThrow()
                .parentThreadId());

        assertEquals(List.of(agent.threadId()),
                gateway.listAgents(parentThreadId, false).stream().map(org.dean.codex.protocol.agent.AgentSummary::threadId).toList());

        var waitResult = gateway.waitAgent(List.of(agent.threadId()), 100);
        assertFalse(waitResult.timedOut());
        assertEquals(agent.threadId(), waitResult.threadId());
        assertEquals("Agent is idle.", waitResult.message());
        assertEquals("done", waitResult.finalAnswer());
        assertNotNull(waitResult.turnId());
        assertEquals("inspect repo", conversationStore.turns(agent.threadId()).get(0).userInput());

        var afterInput = gateway.sendInput(agent.threadId(), new AgentMessage(parentThreadId, agent.threadId(), "Please continue", Instant.now()), false);
        assertEquals(agent.threadId(), afterInput.threadId());
        assertEquals(parentThreadId, afterInput.parentThreadId());
    }

    @Test
    void waitAgentReturnsLatestCompletedResultForIdleAgent() {
        ConversationStore conversationStore = new InMemoryConversationStore();
        DefaultCodexRuntimeGateway gateway = new DefaultCodexRuntimeGateway(
                conversationStore,
                new CompletingTurnExecutor(conversationStore),
                new NoOpContextManager(),
                new NoOpThreadContextReconstructionService(),
                null,
                new NoOpSkillService(),
                4);
        ThreadId parentThreadId = conversationStore.createThread("Parent thread");

        var agent = gateway.spawnAgent(new AgentSpawnRequest(
                parentThreadId,
                "root/worker-1",
                "Investigate the failing tests",
                "worker-1",
                "worker",
                null,
                null,
                null,
                null));
        awaitAgentStatus(gateway, agent.threadId(), AgentStatus.IDLE);

        var waitResult = gateway.waitAgent(List.of(agent.threadId()), 50);

        assertFalse(waitResult.timedOut());
        assertEquals("Agent is idle.", waitResult.message());
        assertEquals("done", waitResult.finalAnswer());
        assertNotNull(waitResult.turnId());
    }

    @Test
    void threadResumeBackfillsPersistedSubAgentsAndExposesThreadTreeNavigation() {
        ConversationStore conversationStore = new InMemoryConversationStore();
        InMemoryThreadHistoryStore historyStore = new InMemoryThreadHistoryStore();
        DefaultCodexRuntimeGateway initialGateway = new DefaultCodexRuntimeGateway(
                conversationStore,
                new CompletingTurnExecutor(conversationStore),
                new NoOpContextManager(),
                new NoOpThreadContextReconstructionService(),
                historyStore,
                new NoOpSkillService(),
                4);

        ThreadId parentThreadId = initialGateway.threadStart("Parent thread").threadId();
        var child = initialGateway.spawnAgent(new AgentSpawnRequest(
                parentThreadId,
                "root/worker-1",
                "Investigate the failing tests",
                "worker-1",
                "worker",
                null,
                null,
                null,
                null));
        awaitAgentStatus(initialGateway, child.threadId(), AgentStatus.IDLE);

        DefaultCodexRuntimeGateway restartedGateway = new DefaultCodexRuntimeGateway(
                conversationStore,
                new CompletingTurnExecutor(conversationStore),
                new NoOpContextManager(),
                new NoOpThreadContextReconstructionService(),
                historyStore,
                new NoOpSkillService(),
                4);

        assertTrue(restartedGateway.loadedThreads().isEmpty());

        restartedGateway.threadResume(parentThreadId);

        assertEquals(parentThreadId, restartedGateway.threadTreeRoot(parentThreadId));
        assertEquals(parentThreadId, restartedGateway.threadTreeRoot(child.threadId()));
        assertEquals(List.of(parentThreadId, child.threadId()),
                restartedGateway.relatedThreads(parentThreadId).stream().map(ThreadSummary::threadId).toList());
        assertEquals(List.of(parentThreadId, child.threadId()),
                restartedGateway.relatedThreads(child.threadId()).stream().map(ThreadSummary::threadId).toList());
        assertTrue(restartedGateway.loadedThreads().contains(parentThreadId));
        assertTrue(restartedGateway.loadedThreads().contains(child.threadId()));
    }

    @Test
    void agentLifecycleSupportsCloseResumeAndDepthLimit() {
        ConversationStore conversationStore = new InMemoryConversationStore();
        ThreadId parentThreadId = conversationStore.createThread("Parent thread");
        DefaultCodexRuntimeGateway gateway = new DefaultCodexRuntimeGateway(
                conversationStore,
                new CompletingTurnExecutor(conversationStore),
                new NoOpContextManager(),
                new NoOpThreadContextReconstructionService(),
                null,
                new NoOpSkillService(),
                1);

        var child = gateway.spawnAgent(new AgentSpawnRequest(
                parentThreadId,
                "root/worker-1",
                null,
                "worker-1",
                "worker",
                null,
                null,
                null,
                null));
        var closed = gateway.closeAgent(child.threadId());
        assertEquals(AgentStatus.SHUTDOWN, closed.status());
        assertFalse(gateway.loadedThreads().contains(child.threadId()));

        var resumed = gateway.resumeAgent(child.threadId());
        assertEquals(AgentStatus.IDLE, resumed.status());
        assertTrue(gateway.loadedThreads().contains(child.threadId()));

        assertThrows(IllegalStateException.class, () -> gateway.spawnAgent(new AgentSpawnRequest(
                child.threadId(),
                "root/worker-1/reviewer",
                null,
                "reviewer",
                "reviewer",
                null,
                null,
                null,
                null)));
    }

    @Test
    void turnStartPublishesStartedItemAndCompletedNotifications() throws Exception {
        ConversationStore store = new InMemoryConversationStore();
        ThreadId threadId = store.createThread("Gateway thread");
        CodexRuntimeGateway gateway = new DefaultCodexRuntimeGateway(store, new CompletingTurnExecutor(store), new NoOpContextManager(), new NoOpThreadContextReconstructionService(), new NoOpSkillService());
        BlockingQueue<RuntimeNotification> notifications = new LinkedBlockingQueue<>();

        try (AutoCloseable ignored = gateway.subscribe(threadId, notifications::add)) {
            gateway.threadResume(threadId);
            RuntimeTurn runtimeTurn = gateway.turnStart(threadId, "inspect repo");
            List<RuntimeNotification> observed = awaitNotifications(notifications, runtimeTurn.turnId(), 3);

            assertEquals(RuntimeNotificationType.TURN_STARTED, observed.get(0).type());
            assertEquals(RuntimeNotificationType.TURN_ITEM, observed.get(1).type());
            assertEquals(RuntimeNotificationType.TURN_COMPLETED, observed.get(2).type());
            assertEquals(TurnStatus.COMPLETED, observed.get(2).status());
            assertEquals("done", store.turn(threadId, runtimeTurn.turnId()).finalAnswer());
        }
    }

    @Test
    void turnInterruptRequestsCauseInterruptedCompletion() throws Exception {
        ConversationStore store = new InMemoryConversationStore();
        ThreadId threadId = store.createThread("Gateway thread");
        CodexRuntimeGateway gateway = new DefaultCodexRuntimeGateway(store, new InterruptibleTurnExecutor(store), new NoOpContextManager(), new NoOpThreadContextReconstructionService(), new NoOpSkillService());
        BlockingQueue<RuntimeNotification> notifications = new LinkedBlockingQueue<>();

        try (AutoCloseable ignored = gateway.subscribe(threadId, notifications::add)) {
            gateway.threadResume(threadId);
            RuntimeTurn runtimeTurn = gateway.turnStart(threadId, "long running task");
            assertTrue(gateway.turnInterrupt(threadId, runtimeTurn.turnId()));

            List<RuntimeNotification> observed = awaitNotifications(notifications, runtimeTurn.turnId(), 2);
            RuntimeNotification completion = observed.get(observed.size() - 1);
            assertEquals(RuntimeNotificationType.TURN_COMPLETED, completion.type());
            assertEquals(TurnStatus.INTERRUPTED, completion.status());
            assertEquals(TurnStatus.INTERRUPTED, store.turn(threadId, runtimeTurn.turnId()).status());
        }
    }

    @Test
    void turnSteerQueuesInputThatIsEmittedAsTurnItem() throws Exception {
        ConversationStore store = new InMemoryConversationStore();
        ThreadId threadId = store.createThread("Gateway thread");
        CodexRuntimeGateway gateway = new DefaultCodexRuntimeGateway(store, new SteeringTurnExecutor(store), new NoOpContextManager(), new NoOpThreadContextReconstructionService(), new NoOpSkillService());
        BlockingQueue<RuntimeNotification> notifications = new LinkedBlockingQueue<>();

        try (AutoCloseable ignored = gateway.subscribe(threadId, notifications::add)) {
            gateway.threadResume(threadId);
            RuntimeTurn runtimeTurn = gateway.turnStart(threadId, "inspect repo");
            assertTrue(gateway.turnSteer(threadId, runtimeTurn.turnId(), "Please focus on tests"));

            List<RuntimeNotification> observed = awaitNotifications(notifications, runtimeTurn.turnId(), 3);
            assertTrue(observed.stream().anyMatch(notification ->
                    notification.type() == RuntimeNotificationType.TURN_ITEM
                            && notification.item() instanceof UserMessageItem userMessageItem
                            && userMessageItem.text().contains("focus on tests")));
        }
    }

    @Test
    void compactThreadDelegatesToContextManager() {
        ConversationStore store = new InMemoryConversationStore();
        ThreadId threadId = store.createThread("Gateway thread");
        ThreadMemory threadMemory = new ThreadMemory("memory-1", threadId, "Compacted earlier thread context.", List.of(), 0, Instant.now());
        ContextManager contextManager = new FixedContextManager(threadMemory);
        CodexRuntimeGateway gateway = new DefaultCodexRuntimeGateway(store, new CompletingTurnExecutor(store), contextManager, new NoOpThreadContextReconstructionService(), new NoOpSkillService());

        ThreadMemory compacted = gateway.compactThread(threadId);

        assertEquals("memory-1", compacted.memoryId());
        assertTrue(gateway.latestThreadMemory(threadId).isPresent());
    }

    @Test
    void reconstructThreadContextDelegatesToReconstructionService() {
        ConversationStore store = new InMemoryConversationStore();
        ThreadId threadId = store.createThread("Gateway thread");
        ReconstructedThreadContext reconstructed = new ReconstructedThreadContext(
                threadId,
                new ThreadMemory("memory-1", threadId, "Compacted earlier thread context.", List.of(), 0, Instant.now()),
                List.of(),
                List.of(),
                List.of(),
                Instant.now());
        CodexRuntimeGateway gateway = new DefaultCodexRuntimeGateway(
                store,
                new CompletingTurnExecutor(store),
                new NoOpContextManager(),
                new FixedThreadContextReconstructionService(reconstructed),
                new NoOpSkillService());

        ReconstructedThreadContext result = gateway.reconstructThreadContext(threadId);

        assertEquals(reconstructed.threadId(), result.threadId());
        assertEquals("memory-1", result.threadMemory().memoryId());
    }

    @Test
    void turnStartRequiresLoadedThread() {
        ConversationStore store = new InMemoryConversationStore();
        ThreadId threadId = store.createThread("Gateway thread");
        CodexRuntimeGateway gateway = new DefaultCodexRuntimeGateway(store, new CompletingTurnExecutor(store), new NoOpContextManager(), new NoOpThreadContextReconstructionService(), new NoOpSkillService());

        IllegalStateException exception = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> gateway.turnStart(threadId, "inspect repo"));

        assertTrue(exception.getMessage().contains("Thread is not loaded"));
    }

    private List<RuntimeNotification> awaitNotifications(BlockingQueue<RuntimeNotification> notifications,
                                                         TurnId turnId,
                                                         int expectedCount) throws InterruptedException {
        List<RuntimeNotification> observed = new ArrayList<>();
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (observed.size() < expectedCount && System.nanoTime() < deadlineNanos) {
            RuntimeNotification notification = notifications.poll(100, TimeUnit.MILLISECONDS);
            if (notification == null || notification.turnId() == null || !notification.turnId().equals(turnId)) {
                continue;
            }
            observed.add(notification);
            if (notification.type() == RuntimeNotificationType.TURN_COMPLETED) {
                break;
            }
        }
        assertTrue(observed.size() >= expectedCount || observed.stream().anyMatch(n -> n.type() == RuntimeNotificationType.TURN_COMPLETED));
        return observed;
    }

    private void awaitTurnStatus(ConversationStore store, ThreadId threadId, TurnId turnId, TurnStatus expectedStatus) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadlineNanos) {
            if (store.turn(threadId, turnId).status() == expectedStatus) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for turn " + turnId.value() + " to reach " + expectedStatus);
    }

    private static final class CompletingTurnExecutor implements TurnExecutor {

        private final ConversationStore store;

        private CompletingTurnExecutor(ConversationStore store) {
            this.store = store;
        }

        @Override
        public CodexTurnResult executeTurn(ThreadId threadId, String input) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CodexTurnResult executeTurn(ThreadId threadId,
                                           TurnId turnId,
                                           String input,
                                           Consumer<TurnItem> itemConsumer,
                                           TurnControl turnControl) {
            ToolCallItem toolCall = new ToolCallItem(new ItemId("item-1"), "READ_FILE", "README.md", Instant.now());
            store.appendTurnItems(threadId, turnId, List.of(toolCall));
            itemConsumer.accept(toolCall);
            store.completeTurn(threadId, turnId, TurnStatus.COMPLETED, "done", Instant.now());
            return new CodexTurnResult(threadId, turnId, TurnStatus.COMPLETED, List.of(toolCall), "done");
        }

        @Override
        public CodexTurnResult resumeTurn(ThreadId threadId, TurnId turnId) {
            throw new UnsupportedOperationException();
        }
    }

    private static void awaitAgentStatus(DefaultCodexRuntimeGateway gateway, ThreadId threadId, AgentStatus expectedStatus) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadlineNanos) {
            AgentStatus status = gateway.listAgents(null, true).stream()
                    .filter(agent -> agent.threadId().equals(threadId))
                    .map(org.dean.codex.protocol.agent.AgentSummary::status)
                    .findFirst()
                    .orElse(null);
            if (status == expectedStatus) {
                return;
            }
            try {
                Thread.sleep(10);
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Timed out waiting for agent status " + expectedStatus + " for " + threadId.value());
    }

    private static final class InterruptibleTurnExecutor implements TurnExecutor {

        private final ConversationStore store;

        private InterruptibleTurnExecutor(ConversationStore store) {
            this.store = store;
        }

        @Override
        public CodexTurnResult executeTurn(ThreadId threadId, String input) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CodexTurnResult executeTurn(ThreadId threadId,
                                           TurnId turnId,
                                           String input,
                                           Consumer<TurnItem> itemConsumer,
                                           TurnControl turnControl) {
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!turnControl.interruptionRequested() && System.nanoTime() < deadlineNanos) {
                try {
                    Thread.sleep(10);
                }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            store.completeTurn(threadId, turnId, TurnStatus.INTERRUPTED, "Turn interrupted.", Instant.now());
            return new CodexTurnResult(threadId, turnId, TurnStatus.INTERRUPTED, List.of(), "Turn interrupted.");
        }

        @Override
        public CodexTurnResult resumeTurn(ThreadId threadId, TurnId turnId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class SteeringTurnExecutor implements TurnExecutor {

        private final ConversationStore store;

        private SteeringTurnExecutor(ConversationStore store) {
            this.store = store;
        }

        @Override
        public CodexTurnResult executeTurn(ThreadId threadId, String input) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CodexTurnResult executeTurn(ThreadId threadId,
                                           TurnId turnId,
                                           String input,
                                           Consumer<TurnItem> itemConsumer,
                                           TurnControl turnControl) {
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            List<TurnItem> items = new ArrayList<>();
            while (System.nanoTime() < deadlineNanos) {
                List<String> steeringInputs = turnControl.drainSteeringInputs();
                if (!steeringInputs.isEmpty()) {
                    for (String steeringInput : steeringInputs) {
                        UserMessageItem messageItem = new UserMessageItem(new ItemId("steer-" + steeringInput.hashCode()), steeringInput, Instant.now());
                        store.appendTurnItems(threadId, turnId, List.of(messageItem));
                        itemConsumer.accept(messageItem);
                        items.add(messageItem);
                    }
                    break;
                }
                try {
                    Thread.sleep(10);
                }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            store.completeTurn(threadId, turnId, TurnStatus.COMPLETED, "done", Instant.now());
            return new CodexTurnResult(threadId, turnId, TurnStatus.COMPLETED, items, "done");
        }

        @Override
        public CodexTurnResult resumeTurn(ThreadId threadId, TurnId turnId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class ForkingTurnExecutor implements TurnExecutor {

        private final ConversationStore conversationStore;
        private final ThreadHistoryStore threadHistoryStore;

        private ForkingTurnExecutor(ConversationStore conversationStore, ThreadHistoryStore threadHistoryStore) {
            this.conversationStore = conversationStore;
            this.threadHistoryStore = threadHistoryStore;
        }

        @Override
        public CodexTurnResult executeTurn(ThreadId threadId, String input) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CodexTurnResult executeTurn(ThreadId threadId,
                                           TurnId turnId,
                                           String input,
                                           Consumer<TurnItem> itemConsumer,
                                           TurnControl turnControl) {
            Instant now = Instant.parse("2026-04-01T00:00:10Z");
            UserMessageItem userMessageItem = new UserMessageItem(new ItemId("child-user-" + turnId.value()), input, now);
            AgentMessageItem assistantMessageItem = new AgentMessageItem(new ItemId("child-assistant-" + turnId.value()), "handled: " + input, now.plusSeconds(1));
            conversationStore.appendTurnItems(threadId, turnId, List.of(userMessageItem, assistantMessageItem));
            threadHistoryStore.append(threadId, ThreadHistoryMapper.map(turnId, List.of(userMessageItem, assistantMessageItem)));
            conversationStore.completeTurn(threadId, turnId, TurnStatus.COMPLETED, "handled: " + input, now.plusSeconds(2));
            return new CodexTurnResult(threadId, turnId, TurnStatus.COMPLETED, List.of(userMessageItem, assistantMessageItem), "handled: " + input);
        }

        @Override
        public CodexTurnResult resumeTurn(ThreadId threadId, TurnId turnId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class NoOpSkillService implements SkillService {

        @Override
        public List<SkillMetadata> listSkills(boolean forceReload) {
            return List.of(new SkillMetadata("demo", "Demo skill", "Demo skill", "/tmp/demo/SKILL.md", SkillScope.USER, true));
        }

        @Override
        public List<ResolvedSkill> resolveSkills(String input, boolean forceReload) {
            return List.of();
        }
    }

    private static final class NoOpContextManager implements ContextManager {

        @Override
        public Optional<ThreadMemory> latestThreadMemory(ThreadId threadId) {
            return Optional.empty();
        }

        @Override
        public ThreadMemory compactThread(ThreadId threadId) {
            return new ThreadMemory("memory-0", threadId, "No older completed turns are ready for compaction yet.", List.of(), 0, Instant.now());
        }
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

    private static final class NoOpThreadContextReconstructionService implements ThreadContextReconstructionService {

        @Override
        public ReconstructedThreadContext reconstruct(ThreadId threadId) {
            return new ReconstructedThreadContext(threadId, null, List.of(), List.of(), List.of(), Instant.now());
        }
    }

    private static final class FixedThreadContextReconstructionService implements ThreadContextReconstructionService {

        private final ReconstructedThreadContext reconstructedThreadContext;

        private FixedThreadContextReconstructionService(ReconstructedThreadContext reconstructedThreadContext) {
            this.reconstructedThreadContext = reconstructedThreadContext;
        }

        @Override
        public ReconstructedThreadContext reconstruct(ThreadId threadId) {
            return reconstructedThreadContext;
        }
    }
}
