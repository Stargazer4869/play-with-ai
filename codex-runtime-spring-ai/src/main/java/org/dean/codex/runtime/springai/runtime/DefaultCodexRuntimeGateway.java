package org.dean.codex.runtime.springai.runtime;

import jakarta.annotation.PreDestroy;
import org.dean.codex.core.agent.AgentControl;
import org.dean.codex.core.agent.TurnControl;
import org.dean.codex.core.agent.TurnExecutor;
import org.dean.codex.core.context.ContextManager;
import org.dean.codex.core.context.ThreadContextReconstructionService;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.core.history.ThreadHistoryStore;
import org.dean.codex.core.runtime.CodexRuntimeGateway;
import org.dean.codex.protocol.agent.AgentMessage;
import org.dean.codex.protocol.agent.AgentSpawnRequest;
import org.dean.codex.protocol.agent.AgentStatus;
import org.dean.codex.protocol.agent.AgentSummary;
import org.dean.codex.protocol.agent.AgentWaitResult;
import org.dean.codex.protocol.appserver.ThreadForkParams;
import org.dean.codex.protocol.context.ReconstructedThreadContext;
import org.dean.codex.protocol.context.ThreadMemory;
import org.dean.codex.core.skill.SkillService;
import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.ThreadActiveFlag;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.ThreadStatus;
import org.dean.codex.protocol.conversation.ThreadSummary;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.event.CodexTurnResult;
import org.dean.codex.protocol.item.TurnItem;
import org.dean.codex.protocol.runtime.RuntimeNotification;
import org.dean.codex.protocol.runtime.RuntimeNotificationType;
import org.dean.codex.protocol.runtime.RuntimeTurn;
import org.dean.codex.protocol.skill.SkillMetadata;
import org.dean.codex.protocol.history.HistoryCompactionSummaryItem;
import org.dean.codex.protocol.history.ThreadHistoryItem;
import org.dean.codex.runtime.springai.config.CodexProperties;
import org.dean.codex.runtime.springai.history.ThreadHistoryReplay;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Component
public class DefaultCodexRuntimeGateway implements CodexRuntimeGateway, AgentControl {

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();
    private static final int DEFAULT_MAX_AGENT_DEPTH = 4;

    private final ConversationStore conversationStore;
    private final TurnExecutor turnExecutor;
    private final ContextManager contextManager;
    private final ThreadContextReconstructionService threadContextReconstructionService;
    private final ThreadHistoryStore threadHistoryStore;
    private final SkillService skillService;
    private final int maxAgentDepth;
    private final ExecutorService turnExecutionPool;
    private final Map<ThreadId, CopyOnWriteArrayList<Consumer<RuntimeNotification>>> subscribers = new ConcurrentHashMap<>();
    private final Map<TurnId, RunningTurn> runningTurns = new ConcurrentHashMap<>();
    private final Set<ThreadId> loadedThreadIds = ConcurrentHashMap.newKeySet();
    private final Map<ThreadId, ConcurrentLinkedQueue<AgentMessage>> pendingAgentInputs = new ConcurrentHashMap<>();

    public DefaultCodexRuntimeGateway(ConversationStore conversationStore,
                                      TurnExecutor turnExecutor,
                                      ContextManager contextManager,
                                      ThreadContextReconstructionService threadContextReconstructionService,
                                      SkillService skillService) {
        this(conversationStore,
                turnExecutor,
                contextManager,
                threadContextReconstructionService,
                null,
                skillService,
                DEFAULT_MAX_AGENT_DEPTH);
    }

    public DefaultCodexRuntimeGateway(ConversationStore conversationStore,
                                      TurnExecutor turnExecutor,
                                      ContextManager contextManager,
                                      ThreadContextReconstructionService threadContextReconstructionService,
                                      ThreadHistoryStore threadHistoryStore,
                                      SkillService skillService) {
        this(conversationStore,
                turnExecutor,
                contextManager,
                threadContextReconstructionService,
                threadHistoryStore,
                skillService,
                DEFAULT_MAX_AGENT_DEPTH);
    }

    @Autowired
    public DefaultCodexRuntimeGateway(ConversationStore conversationStore,
                                      TurnExecutor turnExecutor,
                                      ContextManager contextManager,
                                      ThreadContextReconstructionService threadContextReconstructionService,
                                      ThreadHistoryStore threadHistoryStore,
                                      SkillService skillService,
                                      CodexProperties codexProperties) {
        this(conversationStore,
                turnExecutor,
                contextManager,
                threadContextReconstructionService,
                threadHistoryStore,
                skillService,
                codexProperties == null ? DEFAULT_MAX_AGENT_DEPTH : codexProperties.getAgent().getMaxDepth());
    }

    public DefaultCodexRuntimeGateway(ConversationStore conversationStore,
                                      TurnExecutor turnExecutor,
                                      ContextManager contextManager,
                                      ThreadContextReconstructionService threadContextReconstructionService,
                                      ThreadHistoryStore threadHistoryStore,
                                      SkillService skillService,
                                      int maxAgentDepth) {
        this.conversationStore = conversationStore;
        this.turnExecutor = turnExecutor;
        this.contextManager = contextManager;
        this.threadContextReconstructionService = threadContextReconstructionService;
        this.threadHistoryStore = threadHistoryStore;
        this.skillService = skillService;
        this.maxAgentDepth = Math.max(0, maxAgentDepth);
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
        markThreadLoaded(threadId);
        ThreadSummary summary = threadSummary(threadId);
        publish(notification(RuntimeNotificationType.THREAD_STARTED, threadId, null, summary, null, null, null, null));
        return summary;
    }

    @Override
    public ThreadSummary threadResume(ThreadId threadId) {
        requireThread(threadId);
        requireNotArchived(threadId);
        markThreadTreeLoaded(threadTreeRoot(threadId));
        ThreadSummary summary = threadSummary(threadId);
        publish(notification(RuntimeNotificationType.THREAD_RESUMED, threadId, null, summary, null, null, null, null));
        return summary;
    }

    @Override
    public List<ThreadSummary> listThreads() {
        return conversationStore.listThreads().stream()
                .map(this::runtimeThreadSummary)
                .toList();
    }

    @Override
    public List<ThreadId> loadedThreads() {
        return conversationStore.listThreads().stream()
                .map(ThreadSummary::threadId)
                .filter(loadedThreadIds::contains)
                .toList();
    }

    @Override
    public ThreadId threadTreeRoot(ThreadId threadId) {
        requireThread(threadId);
        Map<ThreadId, ThreadSummary> byId = conversationStore.listThreads().stream()
                .collect(java.util.stream.Collectors.toMap(ThreadSummary::threadId, summary -> summary));
        ThreadSummary current = byId.get(threadId);
        while (current != null && current.parentThreadId() != null && byId.containsKey(current.parentThreadId())) {
            current = byId.get(current.parentThreadId());
        }
        return current == null ? threadId : current.threadId();
    }

    @Override
    public List<ThreadSummary> relatedThreads(ThreadId threadId) {
        requireThread(threadId);
        ThreadId rootThreadId = threadTreeRoot(threadId);
        List<ThreadSummary> summaries = conversationStore.listThreads();
        Map<ThreadId, ThreadSummary> byId = summaries.stream()
                .collect(java.util.stream.Collectors.toMap(ThreadSummary::threadId, summary -> summary));
        return summaries.stream()
                .filter(summary -> belongsToThreadTree(summary, rootThreadId, byId))
                .sorted(threadTreeComparator(rootThreadId))
                .map(this::runtimeThreadSummary)
                .toList();
    }

    @Override
    public AgentSummary spawnAgent(AgentSpawnRequest request) {
        if (request == null || request.parentThreadId() == null) {
            throw new IllegalArgumentException("parentThreadId is required");
        }
        if (request.taskName() == null || request.taskName().isBlank()) {
            throw new IllegalArgumentException("taskName is required");
        }
        requireThread(request.parentThreadId());
        requireNotArchived(request.parentThreadId());
        ThreadSummary parent = storedThreadSummary(request.parentThreadId());
        if (parent.agentClosedAt() != null) {
            throw new IllegalStateException("Cannot spawn from a closed agent thread: " + request.parentThreadId().value());
        }
        int childDepth = nextAgentDepth(parent, request.depth());
        if (childDepth > maxAgentDepth) {
            throw new IllegalStateException("Agent depth limit reached: " + childDepth + " > " + maxAgentDepth);
        }

        ThreadId childThreadId = conversationStore.forkThread(new ThreadForkParams(
                request.parentThreadId(),
                request.taskName(),
                null,
                request.cwd(),
                request.modelProvider(),
                request.model(),
                org.dean.codex.protocol.conversation.ThreadSource.SUB_AGENT,
                request.nickname(),
                request.role(),
                request.taskName()));
        if (threadHistoryStore != null) {
            threadHistoryStore.replace(childThreadId, threadHistoryStore.read(request.parentThreadId()));
        }
        conversationStore.updateAgentThread(
                childThreadId,
                request.parentThreadId(),
                childDepth,
                null,
                request.nickname(),
                request.role(),
                request.taskName());
        markThreadLoaded(childThreadId);
        enqueueAgentInput(childThreadId, new AgentMessage(
                request.parentThreadId(),
                childThreadId,
                request.prompt(),
                Instant.now()));
        startNextAgentTurnIfIdle(childThreadId);
        return agentSummary(childThreadId);
    }

    @Override
    public AgentSummary sendInput(ThreadId agentThreadId, AgentMessage message, boolean interrupt) {
        requireThread(agentThreadId);
        requireAgentThread(agentThreadId);
        if (storedThreadSummary(agentThreadId).agentClosedAt() != null) {
            throw new IllegalStateException("Agent is closed: " + agentThreadId.value());
        }
        AgentMessage normalizedMessage = new AgentMessage(
                message == null ? null : message.senderThreadId(),
                agentThreadId,
                message == null ? null : message.content(),
                message == null || message.createdAt() == null ? Instant.now() : message.createdAt());
        enqueueAgentInput(agentThreadId, normalizedMessage);
        if (interrupt) {
            runningTurns.values().stream()
                    .filter(turn -> turn.threadId().equals(agentThreadId))
                    .forEach(RunningTurn::requestInterruption);
        }
        startNextAgentTurnIfIdle(agentThreadId);
        return agentSummary(agentThreadId);
    }

    @Override
    public AgentWaitResult waitAgent(List<ThreadId> agentThreadIds, long timeoutMillis) {
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException("timeoutMillis must be >= 1");
        }
        List<ThreadId> targets = resolveAgentTargets(agentThreadIds);
        if (targets.isEmpty()) {
            Instant now = Instant.now();
            return new AgentWaitResult(null, null, null, AgentStatus.NOT_FOUND, true, "No agents available to wait on.", "", now);
        }

        Map<ThreadId, AgentStatus> previousStatuses = new ConcurrentHashMap<>();
        Map<ThreadId, AgentTurnSnapshot> previousSnapshots = new ConcurrentHashMap<>();
        for (ThreadId target : targets) {
            AgentSummary summary = agentSummary(target);
            previousStatuses.put(target, summary.status());
                AgentTurnSnapshot snapshot = latestAgentTurnSnapshot(target);
                previousSnapshots.put(target, snapshot);
                if (!hasPendingAgentInput(target) && !isActiveAgentStatus(summary.status()) && snapshot != null) {
                    return new AgentWaitResult(
                            target,
                        snapshot.turnId(),
                            summary.status(),
                            summary.status(),
                            false,
                            "Agent is idle.",
                            snapshot.finalAnswer(),
                        Instant.now());
            }
        }

        long deadline = System.nanoTime() + (timeoutMillis * 1_000_000L);
        while (System.nanoTime() < deadline) {
            for (ThreadId target : targets) {
                AgentSummary summary = agentSummary(target);
                AgentTurnSnapshot snapshot = latestAgentTurnSnapshot(target);
                boolean mailboxChanged = hasPendingAgentInput(target);
                boolean statusChanged = summary.closed() || summary.status() != previousStatuses.get(target);
                boolean producedTurn = !sameSnapshot(snapshot, previousSnapshots.get(target));
                if (mailboxChanged || statusChanged || producedTurn) {
                    return new AgentWaitResult(
                            target,
                            snapshot == null ? null : snapshot.turnId(),
                            previousStatuses.get(target),
                            summary.status(),
                            false,
                            mailboxChanged
                                    ? "Agent mailbox changed."
                                    : producedTurn
                                    ? "Agent produced a new turn result."
                                    : "Agent status changed.",
                            snapshot == null ? "" : snapshot.finalAnswer(),
                            Instant.now());
                }
            }
            try {
                Thread.sleep(50L);
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        ThreadId firstTarget = targets.get(0);
        AgentSummary summary = agentSummary(firstTarget);
        AgentTurnSnapshot snapshot = latestAgentTurnSnapshot(firstTarget);
        return new AgentWaitResult(
                firstTarget,
                snapshot == null ? null : snapshot.turnId(),
                previousStatuses.get(firstTarget),
                summary.status(),
                true,
                "Wait timed out.",
                snapshot == null ? "" : snapshot.finalAnswer(),
                Instant.now());
    }

    @Override
    public AgentSummary resumeAgent(ThreadId agentThreadId) {
        requireThread(agentThreadId);
        ThreadSummary summary = requireAgentThread(agentThreadId);
        conversationStore.updateAgentThread(
                agentThreadId,
                summary.parentThreadId(),
                summary.agentDepth(),
                null,
                summary.agentNickname(),
                summary.agentRole(),
                summary.agentPath());
        markThreadLoaded(agentThreadId);
        startNextAgentTurnIfIdle(agentThreadId);
        return agentSummary(agentThreadId);
    }

    @Override
    public AgentSummary closeAgent(ThreadId agentThreadId) {
        requireThread(agentThreadId);
        requireAgentThread(agentThreadId);
        List<ThreadId> subtree = collectAgentSubtree(agentThreadId);
        for (ThreadId threadId : subtree) {
            requireNoRunningTurn(threadId, "close agent");
        }
        Instant closedAt = Instant.now();
        for (ThreadId threadId : subtree) {
            ThreadSummary summary = storedThreadSummary(threadId);
            conversationStore.updateAgentThread(
                    threadId,
                    summary.parentThreadId(),
                    summary.agentDepth(),
                    closedAt,
                    summary.agentNickname(),
                    summary.agentRole(),
                    summary.agentPath());
            loadedThreadIds.remove(threadId);
            pendingAgentInputs.remove(threadId);
        }
        return agentSummary(agentThreadId);
    }

    @Override
    public List<AgentSummary> listAgents(ThreadId parentThreadId, boolean recursive) {
        List<ThreadSummary> threads = conversationStore.listThreads().stream()
                .filter(this::isAgentThread)
                .toList();
        if (parentThreadId == null) {
            return threads.stream().map(this::agentSummary).toList();
        }
        if (!recursive) {
            return threads.stream()
                    .filter(thread -> parentThreadId.equals(thread.parentThreadId()))
                    .map(this::agentSummary)
                    .toList();
        }
        Set<ThreadId> descendants = new LinkedHashSet<>();
        collectAgentDescendants(parentThreadId, threads, descendants);
        return threads.stream()
                .filter(thread -> descendants.contains(thread.threadId()))
                .map(this::agentSummary)
                .toList();
    }

    @Override
    public ThreadSummary threadFork(ThreadForkParams params) {
        if (params == null || params.threadId() == null) {
            throw new IllegalArgumentException("threadId is required");
        }
        requireThread(params.threadId());
        ThreadId forkedThreadId = conversationStore.forkThread(params);
        if (threadHistoryStore != null) {
            threadHistoryStore.replace(forkedThreadId, threadHistoryStore.read(params.threadId()));
        }
        markThreadLoaded(forkedThreadId);
        return threadSummary(forkedThreadId);
    }

    @Override
    public ThreadSummary threadUnarchive(ThreadId threadId) {
        requireThread(threadId);
        conversationStore.unarchiveThread(threadId);
        return threadSummary(threadId);
    }

    @Override
    public ThreadSummary threadRollback(ThreadId threadId, int numTurns) {
        requireThread(threadId);
        if (numTurns < 1) {
            throw new IllegalArgumentException("numTurns must be >= 1");
        }
        requireNoRunningTurn(threadId, "rollback");
        conversationStore.rollbackThread(threadId, numTurns);
        trimCanonicalHistory(threadId);
        return threadSummary(threadId);
    }

    @Override
    public ThreadSummary threadArchive(ThreadId threadId) {
        requireThread(threadId);
        requireNoRunningTurn(threadId, "archive");
        conversationStore.archiveThread(threadId);
        loadedThreadIds.remove(threadId);
        return threadSummary(threadId);
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
        requireLoadedThread(threadId);
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
        requireLoadedThread(threadId);
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
            startNextAgentTurnIfIdle(runningTurn.threadId());
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
            startNextAgentTurnIfIdle(runningTurn.threadId());
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
        return runtimeThreadSummary(storedThreadSummary(threadId));
    }

    private ThreadSummary storedThreadSummary(ThreadId threadId) {
        return conversationStore.listThreads().stream()
                .filter(summary -> summary.threadId().equals(threadId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown thread id: " + (threadId == null ? "<null>" : threadId.value())));
    }

    private ThreadSummary runtimeThreadSummary(ThreadSummary summary) {
        AgentStatus runtimeAgentStatus = runtimeAgentStatus(summary, false, false);
        if (!loadedThreadIds.contains(summary.threadId())) {
            return summary.withRuntime(ThreadStatus.NOT_LOADED, List.of(), runtimeAgentStatus);
        }
        List<ConversationTurn> turns = conversationStore.turns(summary.threadId());
        List<ThreadActiveFlag> activeFlags = new ArrayList<>();
        boolean active = false;
        boolean waitingOnApproval = false;
        for (ConversationTurn turn : turns) {
            if (turn.status() == TurnStatus.RUNNING) {
                active = true;
            }
            if (turn.status() == TurnStatus.AWAITING_APPROVAL) {
                active = true;
                waitingOnApproval = true;
                activeFlags.add(ThreadActiveFlag.WAITING_ON_APPROVAL);
            }
        }
        return summary.withRuntime(
                active ? ThreadStatus.ACTIVE : ThreadStatus.IDLE,
                activeFlags,
                runtimeAgentStatus(summary, active, waitingOnApproval));
    }

    private void markThreadLoaded(ThreadId threadId) {
        loadedThreadIds.add(threadId);
    }

    private void markThreadTreeLoaded(ThreadId rootThreadId) {
        relatedThreads(rootThreadId).stream()
                .filter(summary -> !summary.archived())
                .map(ThreadSummary::threadId)
                .forEach(this::markThreadLoaded);
    }

    private void requireLoadedThread(ThreadId threadId) {
        if (!loadedThreadIds.contains(threadId)) {
            throw new IllegalStateException("Thread is not loaded: " + (threadId == null ? "<null>" : threadId.value()));
        }
    }

    private void requireNotArchived(ThreadId threadId) {
        if (storedThreadSummary(threadId).archived()) {
            throw new IllegalStateException("Thread is archived: " + threadId.value());
        }
    }

    private void requireThread(ThreadId threadId) {
        if (!conversationStore.exists(threadId)) {
            throw new IllegalArgumentException("Unknown thread id: " + (threadId == null ? "<null>" : threadId.value()));
        }
    }

    private ThreadSummary requireAgentThread(ThreadId threadId) {
        ThreadSummary summary = storedThreadSummary(threadId);
        if (!isAgentThread(summary)) {
            throw new IllegalArgumentException("Thread is not an agent thread: " + threadId.value());
        }
        return summary;
    }

    private void requireNoRunningTurn(ThreadId threadId, String operation) {
        boolean running = runningTurns.values().stream()
                .anyMatch(turn -> turn.threadId().equals(threadId));
        if (running) {
            throw new IllegalStateException("Cannot " + operation + " thread while a turn is running: " + threadId.value());
        }
    }

    private void trimCanonicalHistory(ThreadId threadId) {
        if (threadHistoryStore == null) {
            return;
        }
        Set<TurnId> remainingTurnIds = conversationStore.turns(threadId).stream()
                .map(ConversationTurn::turnId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<ThreadHistoryItem> visibleHistory = ThreadHistoryReplay.replayVisibleHistory(threadHistoryStore.read(threadId));
        List<ThreadHistoryItem> trimmedHistory = visibleHistory.stream()
                .filter(item -> keepHistoryItem(item, remainingTurnIds))
                .toList();
        threadHistoryStore.replace(threadId, trimmedHistory);
    }

    private boolean keepHistoryItem(ThreadHistoryItem item, Set<TurnId> remainingTurnIds) {
        if (item instanceof HistoryCompactionSummaryItem summaryItem) {
            return summaryItem.anchorTurnId() != null && remainingTurnIds.contains(summaryItem.anchorTurnId());
        }
        TurnId turnId = item.turnId();
        return turnId != null
                && turnId.value() != null
                && !turnId.value().isBlank()
                && remainingTurnIds.contains(turnId);
    }

    private int nextAgentDepth(ThreadSummary parent, Integer requestedDepth) {
        if (requestedDepth != null) {
            return Math.max(0, requestedDepth);
        }
        if (parent == null || parent.agentDepth() == null) {
            return 1;
        }
        return parent.agentDepth() + 1;
    }

    private boolean isAgentThread(ThreadSummary summary) {
        return summary != null
                && (summary.parentThreadId() != null
                || summary.agentPath() != null
                || summary.agentNickname() != null
                || summary.agentRole() != null
                || summary.agentDepth() != null);
    }

    private boolean belongsToThreadTree(ThreadSummary summary,
                                        ThreadId rootThreadId,
                                        Map<ThreadId, ThreadSummary> byId) {
        ThreadSummary current = summary;
        while (current != null) {
            if (current.threadId().equals(rootThreadId)) {
                return true;
            }
            ThreadId parentThreadId = current.parentThreadId();
            current = parentThreadId == null ? null : byId.get(parentThreadId);
        }
        return false;
    }

    private java.util.Comparator<ThreadSummary> threadTreeComparator(ThreadId rootThreadId) {
        return java.util.Comparator
                .comparingInt((ThreadSummary summary) -> summary.threadId().equals(rootThreadId) ? -1 : defaultAgentDepth(summary))
                .thenComparing(summary -> summary.agentPath() == null ? "" : summary.agentPath())
                .thenComparing(ThreadSummary::createdAt);
    }

    private int defaultAgentDepth(ThreadSummary summary) {
        return summary.agentDepth() == null ? 0 : summary.agentDepth();
    }

    private AgentStatus runtimeAgentStatus(ThreadSummary summary, boolean active, boolean waitingOnApproval) {
        if (!isAgentThread(summary)) {
            return null;
        }
        if (summary.agentClosedAt() != null) {
            return AgentStatus.SHUTDOWN;
        }
        if (waitingOnApproval) {
            return AgentStatus.WAITING;
        }
        if (active) {
            return AgentStatus.RUNNING;
        }
        return AgentStatus.IDLE;
    }

    private AgentSummary agentSummary(ThreadId threadId) {
        return agentSummary(threadSummary(threadId));
    }

    private AgentSummary agentSummary(ThreadSummary summary) {
        return new AgentSummary(
                summary.threadId(),
                summary.parentThreadId(),
                summary.agentNickname(),
                summary.agentRole(),
                summary.agentPath(),
                summary.agentDepth(),
                summary.agentStatus(),
                summary.createdAt(),
                summary.updatedAt(),
                summary.agentClosedAt());
    }

    private void enqueueAgentInput(ThreadId threadId, AgentMessage message) {
        if (message == null || message.content() == null || message.content().isBlank()) {
            return;
        }
        pendingAgentInputs.computeIfAbsent(threadId, ignored -> new ConcurrentLinkedQueue<>()).add(message);
    }

    private boolean hasPendingAgentInput(ThreadId threadId) {
        Queue<AgentMessage> mailbox = pendingAgentInputs.get(threadId);
        return mailbox != null && !mailbox.isEmpty();
    }

    private boolean isActiveAgentStatus(AgentStatus status) {
        return status == AgentStatus.RUNNING
                || status == AgentStatus.WAITING
                || status == AgentStatus.PENDING_INIT;
    }

    private void startNextAgentTurnIfIdle(ThreadId threadId) {
        if (threadId == null || !loadedThreadIds.contains(threadId)) {
            return;
        }
        ThreadSummary summary = storedThreadSummary(threadId);
        if (!isAgentThread(summary) || summary.agentClosedAt() != null || summary.archived()) {
            return;
        }
        if (runningTurns.values().stream().anyMatch(turn -> turn.threadId().equals(threadId))) {
            return;
        }
        boolean blockedByPendingTurn = conversationStore.turns(threadId).stream()
                .anyMatch(turn -> turn.status() == TurnStatus.RUNNING || turn.status() == TurnStatus.AWAITING_APPROVAL);
        if (blockedByPendingTurn) {
            return;
        }

        Queue<AgentMessage> mailbox = pendingAgentInputs.get(threadId);
        if (mailbox == null) {
            return;
        }
        AgentMessage nextMessage = mailbox.poll();
        if (nextMessage == null) {
            return;
        }
        String input = nextMessage.content() == null ? "" : nextMessage.content().trim();
        if (input.isBlank()) {
            startNextAgentTurnIfIdle(threadId);
            return;
        }
        turnStart(threadId, input);
    }

    private AgentTurnSnapshot latestAgentTurnSnapshot(ThreadId threadId) {
        return conversationStore.turns(threadId).stream()
                .filter(turn -> turn.completedAt() != null)
                .reduce((first, second) -> second)
                .map(turn -> new AgentTurnSnapshot(turn.turnId(), turn.status(), turn.finalAnswer(), turn.completedAt()))
                .orElse(null);
    }

    private boolean sameSnapshot(AgentTurnSnapshot left, AgentTurnSnapshot right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return java.util.Objects.equals(left.turnId(), right.turnId())
                && java.util.Objects.equals(left.completedAt(), right.completedAt())
                && java.util.Objects.equals(left.status(), right.status())
                && java.util.Objects.equals(left.finalAnswer(), right.finalAnswer());
    }

    private List<ThreadId> resolveAgentTargets(List<ThreadId> requestedTargets) {
        if (requestedTargets != null && !requestedTargets.isEmpty()) {
            return List.copyOf(requestedTargets);
        }
        return listAgents(null, true).stream()
                .map(AgentSummary::threadId)
                .toList();
    }

    private List<ThreadId> collectAgentSubtree(ThreadId rootThreadId) {
        List<ThreadSummary> agents = conversationStore.listThreads().stream()
                .filter(this::isAgentThread)
                .toList();
        LinkedHashSet<ThreadId> descendants = new LinkedHashSet<>();
        descendants.add(rootThreadId);
        collectAgentDescendants(rootThreadId, agents, descendants);
        return List.copyOf(descendants);
    }

    private void collectAgentDescendants(ThreadId parentThreadId, List<ThreadSummary> threads, Set<ThreadId> descendants) {
        for (ThreadSummary thread : threads) {
            if (parentThreadId.equals(thread.parentThreadId()) && descendants.add(thread.threadId())) {
                collectAgentDescendants(thread.threadId(), threads, descendants);
            }
        }
    }

    private record AgentTurnSnapshot(TurnId turnId,
                                     TurnStatus status,
                                     String finalAnswer,
                                     Instant completedAt) {
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
