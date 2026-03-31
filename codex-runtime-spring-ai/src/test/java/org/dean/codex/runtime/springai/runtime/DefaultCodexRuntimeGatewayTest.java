package org.dean.codex.runtime.springai.runtime;

import org.dean.codex.core.agent.TurnExecutor;
import org.dean.codex.core.agent.TurnControl;
import org.dean.codex.core.context.ContextManager;
import org.dean.codex.core.context.ThreadContextReconstructionService;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.core.conversation.InMemoryConversationStore;
import org.dean.codex.core.runtime.CodexRuntimeGateway;
import org.dean.codex.core.skill.ResolvedSkill;
import org.dean.codex.core.skill.SkillService;
import org.dean.codex.protocol.context.ReconstructedThreadContext;
import org.dean.codex.protocol.context.ThreadMemory;
import org.dean.codex.protocol.conversation.ItemId;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.event.CodexTurnResult;
import org.dean.codex.protocol.item.ToolCallItem;
import org.dean.codex.protocol.item.TurnItem;
import org.dean.codex.protocol.item.UserMessageItem;
import org.dean.codex.protocol.runtime.RuntimeNotification;
import org.dean.codex.protocol.runtime.RuntimeNotificationType;
import org.dean.codex.protocol.runtime.RuntimeTurn;
import org.dean.codex.protocol.skill.SkillMetadata;
import org.dean.codex.protocol.skill.SkillScope;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        }
    }

    @Test
    void turnStartPublishesStartedItemAndCompletedNotifications() throws Exception {
        ConversationStore store = new InMemoryConversationStore();
        ThreadId threadId = store.createThread("Gateway thread");
        CodexRuntimeGateway gateway = new DefaultCodexRuntimeGateway(store, new CompletingTurnExecutor(store), new NoOpContextManager(), new NoOpThreadContextReconstructionService(), new NoOpSkillService());
        BlockingQueue<RuntimeNotification> notifications = new LinkedBlockingQueue<>();

        try (AutoCloseable ignored = gateway.subscribe(threadId, notifications::add)) {
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
