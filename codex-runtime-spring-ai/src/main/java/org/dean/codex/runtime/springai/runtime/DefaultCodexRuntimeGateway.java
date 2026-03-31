package org.dean.codex.runtime.springai.runtime;

import jakarta.annotation.PreDestroy;
import org.dean.codex.core.agent.TurnControl;
import org.dean.codex.core.agent.TurnExecutor;
import org.dean.codex.core.context.ContextManager;
import org.dean.codex.core.context.ThreadContextReconstructionService;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.core.runtime.CodexRuntimeGateway;
import org.dean.codex.protocol.context.ReconstructedThreadContext;
import org.dean.codex.protocol.context.ThreadMemory;
import org.dean.codex.core.skill.SkillService;
import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.ThreadSummary;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.event.CodexTurnResult;
import org.dean.codex.protocol.item.TurnItem;
import org.dean.codex.protocol.runtime.RuntimeNotification;
import org.dean.codex.protocol.runtime.RuntimeNotificationType;
import org.dean.codex.protocol.runtime.RuntimeTurn;
import org.dean.codex.protocol.skill.SkillMetadata;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Component
public class DefaultCodexRuntimeGateway implements CodexRuntimeGateway {

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final ConversationStore conversationStore;
    private final TurnExecutor turnExecutor;
    private final ContextManager contextManager;
    private final ThreadContextReconstructionService threadContextReconstructionService;
    private final SkillService skillService;
    private final ExecutorService turnExecutionPool;
    private final Map<ThreadId, CopyOnWriteArrayList<Consumer<RuntimeNotification>>> subscribers = new ConcurrentHashMap<>();
    private final Map<TurnId, RunningTurn> runningTurns = new ConcurrentHashMap<>();

    public DefaultCodexRuntimeGateway(ConversationStore conversationStore,
                                      TurnExecutor turnExecutor,
                                      ContextManager contextManager,
                                      ThreadContextReconstructionService threadContextReconstructionService,
                                      SkillService skillService) {
        this.conversationStore = conversationStore;
        this.turnExecutor = turnExecutor;
        this.contextManager = contextManager;
        this.threadContextReconstructionService = threadContextReconstructionService;
        this.skillService = skillService;
        this.turnExecutionPool = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            thread.setName("codex-runtime-" + THREAD_COUNTER.incrementAndGet());
            return thread;
        });
    }

    @Override
    public ThreadSummary threadStart(String title) {
        ThreadId threadId = conversationStore.createThread(title);
        ThreadSummary summary = threadSummary(threadId);
        publish(notification(RuntimeNotificationType.THREAD_STARTED, threadId, null, summary, null, null, null, null));
        return summary;
    }

    @Override
    public ThreadSummary threadResume(ThreadId threadId) {
        ThreadSummary summary = threadSummary(threadId);
        publish(notification(RuntimeNotificationType.THREAD_RESUMED, threadId, null, summary, null, null, null, null));
        return summary;
    }

    @Override
    public List<ThreadSummary> listThreads() {
        return conversationStore.listThreads();
    }

    @Override
    public List<ConversationTurn> turns(ThreadId threadId) {
        return conversationStore.turns(threadId);
    }

    @Override
    public ConversationTurn turn(ThreadId threadId, TurnId turnId) {
        return conversationStore.turn(threadId, turnId);
    }

    @Override
    public ReconstructedThreadContext reconstructThreadContext(ThreadId threadId) {
        requireThread(threadId);
        return threadContextReconstructionService.reconstruct(threadId);
    }

    @Override
    public Optional<ThreadMemory> latestThreadMemory(ThreadId threadId) {
        requireThread(threadId);
        return contextManager.latestThreadMemory(threadId);
    }

    @Override
    public ThreadMemory compactThread(ThreadId threadId) {
        requireThread(threadId);
        return contextManager.compactThread(threadId);
    }

    @Override
    public List<SkillMetadata> listSkills(boolean forceReload) {
        return skillService.listSkills(forceReload);
    }

    @Override
    public RuntimeTurn turnStart(ThreadId threadId, String input) {
        requireThread(threadId);
        Instant startedAt = Instant.now();
        TurnId turnId = conversationStore.startTurn(threadId, input, startedAt);
        RunningTurn runningTurn = new RunningTurn(threadId, turnId, startedAt);
        runningTurns.put(turnId, runningTurn);

        RuntimeTurn runtimeTurn = new RuntimeTurn(threadId, turnId, TurnStatus.RUNNING, startedAt, null);
        publish(notification(RuntimeNotificationType.TURN_STARTED, threadId, turnId, null, runtimeTurn, null, TurnStatus.RUNNING, null));
        turnExecutionPool.submit(() -> executeStartedTurn(runningTurn, input));
        return runtimeTurn;
    }

    @Override
    public RuntimeTurn turnResume(ThreadId threadId, TurnId turnId) {
        requireThread(threadId);
        ConversationTurn turn = conversationStore.turn(threadId, turnId);
        RunningTurn runningTurn = new RunningTurn(threadId, turnId, turn.startedAt());
        runningTurns.put(turnId, runningTurn);

        RuntimeTurn runtimeTurn = new RuntimeTurn(threadId, turnId, TurnStatus.RUNNING, turn.startedAt(), null);
        publish(notification(RuntimeNotificationType.TURN_STARTED, threadId, turnId, null, runtimeTurn, null, TurnStatus.RUNNING, null));
        turnExecutionPool.submit(() -> resumeExistingTurn(runningTurn));
        return runtimeTurn;
    }

    @Override
    public boolean turnInterrupt(ThreadId threadId, TurnId turnId) {
        RunningTurn runningTurn = runningTurns.get(turnId);
        if (runningTurn == null || !runningTurn.threadId().equals(threadId)) {
            return false;
        }
        runningTurn.requestInterruption();
        return true;
    }

    @Override
    public boolean turnSteer(ThreadId threadId, TurnId turnId, String input) {
        RunningTurn runningTurn = runningTurns.get(turnId);
        if (runningTurn == null || !runningTurn.threadId().equals(threadId)) {
            return false;
        }
        if (input == null || input.isBlank()) {
            return false;
        }
        runningTurn.addSteeringInput(input.trim());
        return true;
    }

    @Override
    public AutoCloseable subscribe(ThreadId threadId, Consumer<RuntimeNotification> listener) {
        requireThread(threadId);
        CopyOnWriteArrayList<Consumer<RuntimeNotification>> listeners = subscribers.computeIfAbsent(
                threadId,
                ignored -> new CopyOnWriteArrayList<>());
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @PreDestroy
    public void shutdown() {
        turnExecutionPool.shutdownNow();
    }

    private void executeStartedTurn(RunningTurn runningTurn, String input) {
        try {
            CodexTurnResult result = turnExecutor.executeTurn(
                    runningTurn.threadId(),
                    runningTurn.turnId(),
                    input,
                    item -> publish(turnItemNotification(runningTurn, item)),
                    runningTurn);
            publishCompletion(runningTurn, result);
        }
        catch (Exception exception) {
            publishFailure(runningTurn, exception);
        }
        finally {
            runningTurns.remove(runningTurn.turnId());
        }
    }

    private void resumeExistingTurn(RunningTurn runningTurn) {
        try {
            CodexTurnResult result = turnExecutor.resumeTurn(
                    runningTurn.threadId(),
                    runningTurn.turnId(),
                    item -> publish(turnItemNotification(runningTurn, item)),
                    runningTurn);
            publishCompletion(runningTurn, result);
        }
        catch (Exception exception) {
            publishFailure(runningTurn, exception);
        }
        finally {
            runningTurns.remove(runningTurn.turnId());
        }
    }

    private void publishCompletion(RunningTurn runningTurn, CodexTurnResult result) {
        ConversationTurn storedTurn = conversationStore.turn(runningTurn.threadId(), runningTurn.turnId());
        RuntimeTurn runtimeTurn = new RuntimeTurn(
                runningTurn.threadId(),
                runningTurn.turnId(),
                storedTurn.status(),
                storedTurn.startedAt(),
                storedTurn.completedAt());
        publish(notification(
                RuntimeNotificationType.TURN_COMPLETED,
                runningTurn.threadId(),
                runningTurn.turnId(),
                null,
                runtimeTurn,
                null,
                result.status(),
                storedTurn.finalAnswer()));
    }

    private void publishFailure(RunningTurn runningTurn, Exception exception) {
        ConversationTurn storedTurn = conversationStore.turn(runningTurn.threadId(), runningTurn.turnId());
        RuntimeTurn runtimeTurn = new RuntimeTurn(
                runningTurn.threadId(),
                runningTurn.turnId(),
                storedTurn.status(),
                storedTurn.startedAt(),
                storedTurn.completedAt());
        publish(notification(
                RuntimeNotificationType.TURN_COMPLETED,
                runningTurn.threadId(),
                runningTurn.turnId(),
                null,
                runtimeTurn,
                null,
                storedTurn.status(),
                storedTurn.finalAnswer() == null || storedTurn.finalAnswer().isBlank()
                        ? "The runtime failed: " + (exception.getMessage() == null ? "Unknown error" : exception.getMessage())
                        : storedTurn.finalAnswer()));
    }

    private RuntimeNotification turnItemNotification(RunningTurn runningTurn, TurnItem item) {
        RuntimeTurn runtimeTurn = new RuntimeTurn(
                runningTurn.threadId(),
                runningTurn.turnId(),
                TurnStatus.RUNNING,
                runningTurn.startedAt(),
                null);
        return notification(
                RuntimeNotificationType.TURN_ITEM,
                runningTurn.threadId(),
                runningTurn.turnId(),
                null,
                runtimeTurn,
                item,
                TurnStatus.RUNNING,
                null);
    }

    private RuntimeNotification notification(RuntimeNotificationType type,
                                             ThreadId threadId,
                                             TurnId turnId,
                                             ThreadSummary thread,
                                             RuntimeTurn turn,
                                             TurnItem item,
                                             TurnStatus status,
                                             String finalAnswer) {
        return new RuntimeNotification(type, threadId, turnId, thread, turn, item, status, finalAnswer, Instant.now());
    }

    private void publish(RuntimeNotification notification) {
        CopyOnWriteArrayList<Consumer<RuntimeNotification>> listeners = subscribers.get(notification.threadId());
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        for (Consumer<RuntimeNotification> listener : listeners) {
            try {
                listener.accept(notification);
            }
            catch (Exception ignored) {
                // Subscribers should not break the runtime loop.
            }
        }
    }

    private ThreadSummary threadSummary(ThreadId threadId) {
        return conversationStore.listThreads().stream()
                .filter(summary -> summary.threadId().equals(threadId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown thread id: " + (threadId == null ? "<null>" : threadId.value())));
    }

    private void requireThread(ThreadId threadId) {
        if (!conversationStore.exists(threadId)) {
            throw new IllegalArgumentException("Unknown thread id: " + (threadId == null ? "<null>" : threadId.value()));
        }
    }

    private static final class RunningTurn implements TurnControl {

        private final ThreadId threadId;
        private final TurnId turnId;
        private final Instant startedAt;
        private final AtomicBoolean interruptionRequested = new AtomicBoolean(false);
        private final Queue<String> steeringInputs = new ConcurrentLinkedQueue<>();

        private RunningTurn(ThreadId threadId, TurnId turnId, Instant startedAt) {
            this.threadId = threadId;
            this.turnId = turnId;
            this.startedAt = startedAt;
        }

        private ThreadId threadId() {
            return threadId;
        }

        private TurnId turnId() {
            return turnId;
        }

        private Instant startedAt() {
            return startedAt;
        }

        private void requestInterruption() {
            interruptionRequested.set(true);
        }

        private void addSteeringInput(String input) {
            steeringInputs.add(input);
        }

        @Override
        public boolean interruptionRequested() {
            return interruptionRequested.get();
        }

        @Override
        public List<String> drainSteeringInputs() {
            List<String> drained = new java.util.ArrayList<>();
            String input;
            while ((input = steeringInputs.poll()) != null) {
                drained.add(input);
            }
            return List.copyOf(drained);
        }
    }
}
