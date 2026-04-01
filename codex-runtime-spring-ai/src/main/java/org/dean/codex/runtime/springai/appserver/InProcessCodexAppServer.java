package org.dean.codex.runtime.springai.appserver;

import org.dean.codex.core.appserver.CodexAppServer;
import org.dean.codex.core.appserver.CodexAppServerSession;
import org.dean.codex.core.runtime.CodexRuntimeGateway;
import org.dean.codex.protocol.appserver.AppServerNotification;
import org.dean.codex.protocol.appserver.InitializeParams;
import org.dean.codex.protocol.appserver.InitializeResponse;
import org.dean.codex.protocol.appserver.InitializedNotification;
import org.dean.codex.protocol.appserver.SkillsListParams;
import org.dean.codex.protocol.appserver.SkillsListResponse;
import org.dean.codex.protocol.appserver.ThreadArchiveParams;
import org.dean.codex.protocol.appserver.ThreadArchiveResponse;
import org.dean.codex.protocol.appserver.ThreadCompaction;
import org.dean.codex.protocol.appserver.ThreadCompactStartParams;
import org.dean.codex.protocol.appserver.ThreadCompactStartResponse;
import org.dean.codex.protocol.appserver.ThreadCompactionStartedNotification;
import org.dean.codex.protocol.appserver.ThreadCompactedNotification;
import org.dean.codex.protocol.appserver.ThreadForkParams;
import org.dean.codex.protocol.appserver.ThreadForkResponse;
import org.dean.codex.protocol.appserver.ThreadListParams;
import org.dean.codex.protocol.appserver.ThreadListResponse;
import org.dean.codex.protocol.appserver.ThreadLoadedListParams;
import org.dean.codex.protocol.appserver.ThreadLoadedListResponse;
import org.dean.codex.protocol.appserver.ThreadReadParams;
import org.dean.codex.protocol.appserver.ThreadReadResponse;
import org.dean.codex.protocol.appserver.ThreadRollbackParams;
import org.dean.codex.protocol.appserver.ThreadRollbackResponse;
import org.dean.codex.protocol.appserver.ThreadResumeParams;
import org.dean.codex.protocol.appserver.ThreadResumeResponse;
import org.dean.codex.protocol.appserver.ThreadSortKey;
import org.dean.codex.protocol.appserver.ThreadStartParams;
import org.dean.codex.protocol.appserver.ThreadStartResponse;
import org.dean.codex.protocol.appserver.ThreadStartedNotification;
import org.dean.codex.protocol.appserver.ThreadSourceKind;
import org.dean.codex.protocol.appserver.ThreadUnarchiveParams;
import org.dean.codex.protocol.appserver.ThreadUnarchiveResponse;
import org.dean.codex.protocol.appserver.TurnCompletedNotification;
import org.dean.codex.protocol.appserver.TurnInterruptParams;
import org.dean.codex.protocol.appserver.TurnInterruptResponse;
import org.dean.codex.protocol.appserver.TurnItemNotification;
import org.dean.codex.protocol.appserver.TurnResumeParams;
import org.dean.codex.protocol.appserver.TurnResumeResponse;
import org.dean.codex.protocol.appserver.TurnStartParams;
import org.dean.codex.protocol.appserver.TurnStartResponse;
import org.dean.codex.protocol.appserver.TurnStartedNotification;
import org.dean.codex.protocol.appserver.TurnSteerParams;
import org.dean.codex.protocol.appserver.TurnSteerResponse;
import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.ThreadSummary;
import org.dean.codex.protocol.runtime.RuntimeNotification;
import org.dean.codex.protocol.runtime.RuntimeNotificationType;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class InProcessCodexAppServer implements CodexAppServer {

    private final CodexRuntimeGateway runtimeGateway;

    public InProcessCodexAppServer(CodexRuntimeGateway runtimeGateway) {
        this.runtimeGateway = runtimeGateway;
    }

    @Override
    public CodexAppServerSession connect() {
        return new Session();
    }

    private final class Session implements CodexAppServerSession {

        private final CopyOnWriteArrayList<Consumer<AppServerNotification>> subscribers = new CopyOnWriteArrayList<>();
        private final Map<ThreadId, AutoCloseable> runtimeSubscriptions = new ConcurrentHashMap<>();
        private final Set<String> optOutNotificationMethods = new HashSet<>();
        private boolean initializeCalled;
        private boolean initializedAcknowledged;

        @Override
        public synchronized InitializeResponse initialize(InitializeParams params) {
            if (initializeCalled) {
                throw new IllegalStateException("Already initialized");
            }
            initializeCalled = true;
            optOutNotificationMethods.clear();
            if (params != null && params.capabilities() != null) {
                optOutNotificationMethods.addAll(params.capabilities().optOutNotificationMethods());
            }
            return new InitializeResponse(
                    buildUserAgent(params),
                    Path.of(System.getProperty("user.home"), ".codex-java").toAbsolutePath().normalize().toString(),
                    "desktop",
                    System.getProperty("os.name", "unknown"));
        }

        @Override
        public synchronized void initialized(InitializedNotification notification) {
            if (!initializeCalled) {
                throw new IllegalStateException("Not initialized");
            }
            initializedAcknowledged = true;
        }

        @Override
        public ThreadStartResponse threadStart(ThreadStartParams params) {
            ensureReady();
            ThreadSummary thread = runtimeGateway.threadStart(params == null ? "" : params.title());
            ensureRuntimeSubscriptions(List.of(thread.threadId()));
            publish(new ThreadStartedNotification(thread));
            return new ThreadStartResponse(thread);
        }

        @Override
        public ThreadResumeResponse threadResume(ThreadResumeParams params) {
            ensureReady();
            ThreadSummary thread = runtimeGateway.threadResume(requireThreadId(params));
            ensureRuntimeSubscriptions(loadedRelatedThreadIds(thread.threadId()));
            return new ThreadResumeResponse(thread);
        }

        @Override
        public ThreadListResponse threadList(ThreadListParams params) {
            ensureReady();
            return paginateThreads(filterThreads(runtimeGateway.listThreads(), params), params);
        }

        @Override
        public ThreadLoadedListResponse threadLoadedList(ThreadLoadedListParams params) {
            ensureReady();
            List<ThreadId> loadedThreadIds = runtimeGateway.loadedThreads();
            int offset = decodeCursor(params == null ? null : params.cursor());
            int limit = normalizeLimit(params == null ? null : params.limit(), loadedThreadIds.size());
            int endExclusive = Math.min(offset + limit, loadedThreadIds.size());
            List<ThreadId> page = loadedThreadIds.subList(Math.min(offset, loadedThreadIds.size()), endExclusive);
            String nextCursor = endExclusive < loadedThreadIds.size() ? Integer.toString(endExclusive) : null;
            return new ThreadLoadedListResponse(page, nextCursor);
        }

        @Override
        public ThreadReadResponse threadRead(ThreadReadParams params) {
            ensureReady();
            ThreadId threadId = requireThreadId(params);
            ThreadSummary thread = runtimeGateway.listThreads().stream()
                    .filter(summary -> summary.threadId().equals(threadId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown thread id: " + threadId.value()));
            List<ThreadSummary> threadTree = runtimeGateway.relatedThreads(threadId);
            ensureRuntimeSubscriptions(threadTree.stream()
                    .filter(ThreadSummary::loaded)
                    .map(ThreadSummary::threadId)
                    .toList());
            boolean includeTurns = params != null && params.includeTurns();
            return new ThreadReadResponse(
                    thread,
                    includeTurns ? runtimeGateway.turns(threadId) : List.of(),
                    includeTurns ? runtimeGateway.latestThreadMemory(threadId).orElse(null) : null,
                    includeTurns ? runtimeGateway.reconstructThreadContext(threadId) : null,
                    runtimeGateway.threadTreeRoot(threadId),
                    threadTree.stream()
                            .filter(summary -> !summary.threadId().equals(threadId))
                            .toList());
        }

        @Override
        public ThreadForkResponse threadFork(ThreadForkParams params) {
            ensureReady();
            if (params == null || params.threadId() == null) {
                throw new IllegalArgumentException("threadId is required");
            }
            ThreadSummary thread = runtimeGateway.threadFork(params);
            ensureRuntimeSubscriptions(List.of(thread.threadId()));
            publish(new ThreadStartedNotification(thread));
            return new ThreadForkResponse(thread);
        }

        @Override
        public ThreadArchiveResponse threadArchive(ThreadArchiveParams params) {
            ensureReady();
            return new ThreadArchiveResponse(runtimeGateway.threadArchive(requireThreadId(params)));
        }

        @Override
        public ThreadUnarchiveResponse threadUnarchive(ThreadUnarchiveParams params) {
            ensureReady();
            return new ThreadUnarchiveResponse(runtimeGateway.threadUnarchive(requireThreadId(params)));
        }

        @Override
        public ThreadRollbackResponse threadRollback(ThreadRollbackParams params) {
            ensureReady();
            ThreadId threadId = requireThreadId(params);
            if (params.numTurns() < 1) {
                throw new IllegalArgumentException("numTurns must be >= 1");
            }
            ThreadSummary thread = runtimeGateway.threadRollback(threadId, params.numTurns());
            return new ThreadRollbackResponse(thread, runtimeGateway.turns(threadId));
        }

        @Override
        public ThreadCompactStartResponse threadCompactStart(ThreadCompactStartParams params) {
            ensureReady();
            ThreadId threadId = requireThreadId(params);
            String compactionId = UUID.randomUUID().toString();
            Instant startedAt = Instant.now();
            ThreadCompaction startedCompaction = new ThreadCompaction(
                    compactionId,
                    threadId,
                    List.of(),
                    0,
                    "",
                    startedAt,
                    null);
            publish(new ThreadCompactionStartedNotification(startedCompaction));

            var threadMemory = runtimeGateway.compactThread(threadId);
            ThreadCompaction completedCompaction = new ThreadCompaction(
                    compactionId,
                    threadId,
                    threadMemory.sourceTurnIds(),
                    threadMemory.compactedTurnCount(),
                    threadMemory.summary(),
                    startedAt,
                    threadMemory.createdAt());
            publish(new ThreadCompactedNotification(completedCompaction));
            return new ThreadCompactStartResponse(completedCompaction, threadMemory);
        }

        @Override
        public TurnStartResponse turnStart(TurnStartParams params) {
            ensureReady();
            ThreadId threadId = requireThreadId(params);
            ensureRuntimeSubscriptions(List.of(threadId));
            return new TurnStartResponse(runtimeGateway.turnStart(threadId, params.input()));
        }

        @Override
        public TurnResumeResponse turnResume(TurnResumeParams params) {
            ensureReady();
            ThreadId threadId = requireThreadId(params);
            ensureRuntimeSubscriptions(List.of(threadId));
            return new TurnResumeResponse(runtimeGateway.turnResume(threadId, params.turnId()));
        }

        @Override
        public TurnInterruptResponse turnInterrupt(TurnInterruptParams params) {
            ensureReady();
            boolean accepted = runtimeGateway.turnInterrupt(requireThreadId(params), params.turnId());
            return new TurnInterruptResponse(params.turnId(), accepted);
        }

        @Override
        public TurnSteerResponse turnSteer(TurnSteerParams params) {
            ensureReady();
            boolean accepted = runtimeGateway.turnSteer(requireThreadId(params), params.turnId(), params.input());
            return new TurnSteerResponse(params.turnId(), accepted);
        }

        @Override
        public SkillsListResponse skillsList(SkillsListParams params) {
            ensureReady();
            return new SkillsListResponse(runtimeGateway.listSkills(params != null && params.forceReload()));
        }

        @Override
        public AutoCloseable subscribe(Consumer<AppServerNotification> listener) {
            ensureReady();
            subscribers.add(listener);
            return () -> subscribers.remove(listener);
        }

        @Override
        public void close() throws Exception {
            for (AutoCloseable runtimeSubscription : runtimeSubscriptions.values()) {
                runtimeSubscription.close();
            }
            runtimeSubscriptions.clear();
            subscribers.clear();
        }

        private void ensureReady() {
            if (!initializeCalled || !initializedAcknowledged) {
                throw new IllegalStateException("Not initialized");
            }
        }

        private void ensureRuntimeSubscriptions(List<ThreadId> threadIds) {
            if (threadIds == null || threadIds.isEmpty()) {
                return;
            }
            for (ThreadId threadId : threadIds) {
                if (threadId == null) {
                    continue;
                }
                runtimeSubscriptions.computeIfAbsent(threadId, ignored -> {
                    try {
                        return runtimeGateway.subscribe(threadId, this::publishRuntimeNotification);
                    }
                    catch (Exception exception) {
                        throw new IllegalStateException("Unable to subscribe to runtime notifications for thread " + threadId.value(), exception);
                    }
                });
            }
        }

        private List<ThreadId> loadedRelatedThreadIds(ThreadId threadId) {
            return runtimeGateway.relatedThreads(threadId).stream()
                    .filter(ThreadSummary::loaded)
                    .map(ThreadSummary::threadId)
                    .toList();
        }

        private void publishRuntimeNotification(RuntimeNotification notification) {
            if (notification == null || notification.turn() == null) {
                return;
            }
            AppServerNotification mapped = switch (notification.type()) {
                case TURN_STARTED -> new TurnStartedNotification(notification.turn());
                case TURN_ITEM -> new TurnItemNotification(notification.turn(), notification.item());
                case TURN_COMPLETED -> new TurnCompletedNotification(notification.turn(), notification.finalAnswer());
                case THREAD_STARTED, THREAD_RESUMED -> null;
            };
            if (mapped != null) {
                publish(mapped);
            }
        }

        private void publish(AppServerNotification notification) {
            if (notification == null || optOutNotificationMethods.contains(notification.method())) {
                return;
            }
            for (Consumer<AppServerNotification> subscriber : subscribers) {
                try {
                    subscriber.accept(notification);
                }
                catch (Exception ignored) {
                    // App-server subscribers should not break the runtime.
                }
            }
        }

        private String buildUserAgent(InitializeParams params) {
            if (params == null || params.clientInfo() == null || params.clientInfo().name() == null || params.clientInfo().name().isBlank()) {
                return "codex-java-app-server";
            }
            return params.clientInfo().name().trim();
        }

        private ThreadListResponse paginateThreads(List<ThreadSummary> threads, ThreadListParams params) {
            int offset = decodeCursor(params == null ? null : params.cursor());
            int limit = normalizeLimit(params == null ? null : params.limit(), threads.size());
            int start = Math.min(offset, threads.size());
            int endExclusive = Math.min(start + limit, threads.size());
            List<ThreadSummary> page = threads.subList(start, endExclusive);
            String nextCursor = endExclusive < threads.size() ? Integer.toString(endExclusive) : null;
            return new ThreadListResponse(page, nextCursor);
        }

        private List<ThreadSummary> filterThreads(List<ThreadSummary> threads, ThreadListParams params) {
            java.util.stream.Stream<ThreadSummary> stream = threads.stream();
            if (params != null && params.modelProviders() != null && !params.modelProviders().isEmpty()) {
                Set<String> providers = Set.copyOf(params.modelProviders());
                stream = stream.filter(thread -> thread.modelProvider() != null && providers.contains(thread.modelProvider()));
            }
            if (params != null && params.sourceKinds() != null && !params.sourceKinds().isEmpty()) {
                Set<ThreadSourceKind> sourceKinds = Set.copyOf(params.sourceKinds());
                stream = stream.filter(thread -> sourceKinds.contains(toSourceKind(thread)));
            }
            boolean explicitArchivedFilter = params != null && params.archived() != null;
            boolean archivedOnly = explicitArchivedFilter && params.archived();
            stream = stream.filter(thread -> explicitArchivedFilter ? archivedOnly == thread.archived() : !thread.archived());
            if (params != null && params.cwd() != null && !params.cwd().isBlank()) {
                stream = stream.filter(thread -> params.cwd().equals(thread.cwd()));
            }
            if (params != null && params.searchTerm() != null && !params.searchTerm().isBlank()) {
                String needle = params.searchTerm().toLowerCase();
                stream = stream.filter(thread -> {
                    String title = thread.title() == null ? "" : thread.title().toLowerCase();
                    String preview = thread.preview() == null ? "" : thread.preview().toLowerCase();
                    return title.contains(needle) || preview.contains(needle);
                });
            }
            java.util.Comparator<ThreadSummary> comparator = ((params == null ? null : params.sortKey()) == ThreadSortKey.CREATED_AT)
                    ? java.util.Comparator.comparing(ThreadSummary::createdAt).reversed()
                    : java.util.Comparator.comparing(ThreadSummary::updatedAt).reversed();
            return stream.sorted(comparator).toList();
        }

        private ThreadSourceKind toSourceKind(ThreadSummary thread) {
            return switch (thread.source()) {
                case CLI -> ThreadSourceKind.CLI;
                case APP_SERVER -> ThreadSourceKind.APP_SERVER;
                case EXEC -> ThreadSourceKind.EXEC;
                case SUB_AGENT -> ThreadSourceKind.SUB_AGENT;
                case UNKNOWN -> ThreadSourceKind.UNKNOWN;
            };
        }

        private int decodeCursor(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return 0;
            }
            try {
                return Math.max(0, Integer.parseInt(cursor.trim()));
            }
            catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid cursor: " + cursor);
            }
        }

        private int normalizeLimit(Integer limit, int defaultValue) {
            if (limit == null) {
                return Math.max(0, defaultValue);
            }
            if (limit < 0) {
                throw new IllegalArgumentException("limit must be >= 0");
            }
            return limit;
        }
    }

    private ThreadId requireThreadId(ThreadResumeParams params) {
        if (params == null || params.threadId() == null) {
            throw new IllegalArgumentException("threadId is required");
        }
        return params.threadId();
    }

    private ThreadId requireThreadId(ThreadReadParams params) {
        if (params == null || params.threadId() == null) {
            throw new IllegalArgumentException("threadId is required");
        }
        return params.threadId();
    }

    private ThreadId requireThreadId(ThreadCompactStartParams params) {
        if (params == null || params.threadId() == null) {
            throw new IllegalArgumentException("threadId is required");
        }
        return params.threadId();
    }

    private ThreadId requireThreadId(ThreadArchiveParams params) {
        if (params == null || params.threadId() == null) {
            throw new IllegalArgumentException("threadId is required");
        }
        return params.threadId();
    }

    private ThreadId requireThreadId(ThreadUnarchiveParams params) {
        if (params == null || params.threadId() == null) {
            throw new IllegalArgumentException("threadId is required");
        }
        return params.threadId();
    }

    private ThreadId requireThreadId(ThreadRollbackParams params) {
        if (params == null || params.threadId() == null) {
            throw new IllegalArgumentException("threadId is required");
        }
        return params.threadId();
    }

    private ThreadId requireThreadId(TurnStartParams params) {
        if (params == null || params.threadId() == null) {
            throw new IllegalArgumentException("threadId is required");
        }
        return params.threadId();
    }

    private ThreadId requireThreadId(TurnInterruptParams params) {
        if (params == null || params.threadId() == null) {
            throw new IllegalArgumentException("threadId is required");
        }
        return params.threadId();
    }

    private ThreadId requireThreadId(TurnSteerParams params) {
        if (params == null || params.threadId() == null) {
            throw new IllegalArgumentException("threadId is required");
        }
        return params.threadId();
    }

    private ThreadId requireThreadId(TurnResumeParams params) {
        if (params == null || params.threadId() == null) {
            throw new IllegalArgumentException("threadId is required");
        }
        return params.threadId();
    }
}
