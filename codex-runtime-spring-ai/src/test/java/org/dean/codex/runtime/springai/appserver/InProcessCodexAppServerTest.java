package org.dean.codex.runtime.springai.appserver;

import org.dean.codex.core.agent.TurnControl;
import org.dean.codex.core.agent.TurnExecutor;
import org.dean.codex.core.appserver.CodexAppServer;
import org.dean.codex.core.appserver.CodexAppServerSession;
import org.dean.codex.core.context.ContextManager;
import org.dean.codex.core.context.ThreadContextReconstructionService;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.core.conversation.InMemoryConversationStore;
import org.dean.codex.core.runtime.CodexRuntimeGateway;
import org.dean.codex.core.skill.ResolvedSkill;
import org.dean.codex.core.skill.SkillService;
import org.dean.codex.protocol.appserver.AppServerCapabilities;
import org.dean.codex.protocol.appserver.AppServerClientInfo;
import org.dean.codex.protocol.appserver.AppServerNotification;
import org.dean.codex.protocol.appserver.InitializeParams;
import org.dean.codex.protocol.appserver.InitializedNotification;
import org.dean.codex.protocol.appserver.SkillsListParams;
import org.dean.codex.protocol.appserver.ThreadArchiveParams;
import org.dean.codex.protocol.appserver.ThreadCompaction;
import org.dean.codex.protocol.appserver.ThreadCompactStartParams;
import org.dean.codex.protocol.appserver.ThreadCompactionStartedNotification;
import org.dean.codex.protocol.appserver.ThreadCompactedNotification;
import org.dean.codex.protocol.appserver.ThreadForkParams;
import org.dean.codex.protocol.appserver.ThreadListParams;
import org.dean.codex.protocol.appserver.ThreadLoadedListParams;
import org.dean.codex.protocol.appserver.ThreadReadParams;
import org.dean.codex.protocol.appserver.ThreadRollbackParams;
import org.dean.codex.protocol.appserver.ThreadResumeParams;
import org.dean.codex.protocol.appserver.ThreadSortKey;
import org.dean.codex.protocol.appserver.ThreadStartParams;
import org.dean.codex.protocol.appserver.ThreadStartedNotification;
import org.dean.codex.protocol.appserver.ThreadSourceKind;
import org.dean.codex.protocol.appserver.ThreadUnarchiveParams;
import org.dean.codex.protocol.appserver.TurnCompletedNotification;
import org.dean.codex.protocol.appserver.TurnItemNotification;
import org.dean.codex.protocol.appserver.TurnStartParams;
import org.dean.codex.protocol.appserver.TurnStartedNotification;
import org.dean.codex.protocol.appserver.TurnSteerParams;
import org.dean.codex.protocol.agent.AgentSpawnRequest;
import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.ItemId;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.ThreadSource;
import org.dean.codex.protocol.conversation.ThreadStatus;
import org.dean.codex.protocol.conversation.ThreadSummary;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.context.ReconstructedThreadContext;
import org.dean.codex.protocol.context.ThreadMemory;
import org.dean.codex.protocol.event.CodexTurnResult;
import org.dean.codex.protocol.item.TurnItem;
import org.dean.codex.protocol.item.UserMessageItem;
import org.dean.codex.protocol.skill.SkillMetadata;
import org.dean.codex.protocol.skill.SkillScope;
import org.dean.codex.runtime.springai.runtime.DefaultCodexRuntimeGateway;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InProcessCodexAppServerTest {

    @Test
    void threadStartPublishesThreadStartedNotification() throws Exception {
        CodexAppServer appServer = appServer(new NoOpTurnExecutor());
        BlockingQueue<AppServerNotification> notifications = new LinkedBlockingQueue<>();

        try (CodexAppServerSession session = initializedSession(appServer);
             AutoCloseable ignored = session.subscribe(notifications::add)) {
            ThreadId threadId = session.threadStart(new ThreadStartParams("App thread")).thread().threadId();
            AppServerNotification notification = notifications.poll(2, TimeUnit.SECONDS);
            assertNotNull(notification);
            assertTrue(notification instanceof ThreadStartedNotification);
            assertEquals(threadId, ((ThreadStartedNotification) notification).thread().threadId());
        }
    }

    @Test
    void threadForkPublishesStartedNotificationAndDivergesFromParent() throws Exception {
        CodexAppServer appServer = appServer(new NoOpTurnExecutor());
        BlockingQueue<AppServerNotification> notifications = new LinkedBlockingQueue<>();

        try (CodexAppServerSession session = initializedSession(appServer);
             AutoCloseable ignored = session.subscribe(notifications::add)) {
            ThreadId parentThreadId = session.threadStart(new ThreadStartParams("Parent thread")).thread().threadId();
            session.turnStart(new TurnStartParams(parentThreadId, "Inspect repo"));

            var forked = session.threadFork(new ThreadForkParams(
                    parentThreadId,
                    "Forked thread",
                    Boolean.TRUE,
                    "/workspace/forked",
                    "openai",
                    "gpt-5.4",
                    ThreadSource.APP_SERVER,
                    "worker-1",
                    "worker",
                    "root/worker-1")).thread();

            assertEquals("Forked thread", forked.title());
            assertEquals("/workspace/forked", forked.cwd());
            assertTrue(forked.ephemeral());

            List<AppServerNotification> observed = awaitNotifications(notifications, 4);
            assertTrue(observed.stream().anyMatch(notification ->
                    notification instanceof ThreadStartedNotification started
                            && started.thread().threadId().equals(forked.threadId())));

            var forkedRead = session.threadRead(new ThreadReadParams(forked.threadId(), true));
            assertEquals(1, forkedRead.turns().size());
            assertTrue(session.threadLoadedList(new ThreadLoadedListParams()).data().contains(forked.threadId()));

            session.turnStart(new TurnStartParams(forked.threadId(), "Write follow-up"));

            assertEquals(1, session.threadRead(new ThreadReadParams(parentThreadId, true)).turns().size());
            assertEquals(2, session.threadRead(new ThreadReadParams(forked.threadId(), true)).turns().size());
        }
    }

    @Test
    void turnStartAndSteerFlowThroughAppServerContract() throws Exception {
        CodexAppServer appServer = appServer(new SteeringTurnExecutor());
        BlockingQueue<AppServerNotification> notifications = new LinkedBlockingQueue<>();

        try (CodexAppServerSession session = initializedSession(appServer);
             AutoCloseable ignored = session.subscribe(notifications::add)) {
            ThreadId threadId = session.threadStart(new ThreadStartParams("App thread")).thread().threadId();
            TurnId turnId = session.turnStart(new TurnStartParams(threadId, "Inspect repo")).turn().turnId();
            assertTrue(session.turnSteer(new TurnSteerParams(threadId, turnId, "Please focus on tests")).accepted());

            List<AppServerNotification> observed = awaitNotifications(notifications);
            AppServerNotification first = observed.get(0);
            assertTrue(first instanceof ThreadStartedNotification || first instanceof TurnStartedNotification);
            assertTrue(observed.stream().anyMatch(TurnStartedNotification.class::isInstance));
            assertTrue(observed.stream().anyMatch(notification ->
                    notification instanceof TurnItemNotification item
                            && item.item() instanceof UserMessageItem userMessageItem
                            && userMessageItem.text().contains("focus on tests")));
            assertTrue(observed.stream().anyMatch(TurnCompletedNotification.class::isInstance));
        }
    }

    @Test
    void skillsListDelegatesToRuntimeSkills() throws Exception {
        CodexAppServer appServer = appServer(new NoOpTurnExecutor());

        try (CodexAppServerSession session = initializedSession(appServer)) {
            var response = session.skillsList(new SkillsListParams(false));

            assertEquals(1, response.skills().size());
            assertEquals("reviewer", response.skills().get(0).name());
        }
    }

    @Test
    void threadCompactStartPublishesThreadCompactedNotification() throws Exception {
        CodexAppServer appServer = appServer(new NoOpTurnExecutor());
        BlockingQueue<AppServerNotification> notifications = new LinkedBlockingQueue<>();

        try (CodexAppServerSession session = initializedSession(appServer);
             AutoCloseable ignored = session.subscribe(notifications::add)) {
            ThreadId threadId = session.threadStart(new ThreadStartParams("App thread")).thread().threadId();
            var response = session.threadCompactStart(new ThreadCompactStartParams(threadId));

            List<AppServerNotification> observed = awaitNotifications(notifications, 3);
            assertTrue(observed.stream().anyMatch(ThreadCompactionStartedNotification.class::isInstance));
            assertTrue(observed.stream().anyMatch(ThreadCompactedNotification.class::isInstance));

            ThreadCompactionStartedNotification started = (ThreadCompactionStartedNotification) observed.stream()
                    .filter(ThreadCompactionStartedNotification.class::isInstance)
                    .findFirst()
                    .orElseThrow();
            ThreadCompactedNotification completed = (ThreadCompactedNotification) observed.stream()
                    .filter(ThreadCompactedNotification.class::isInstance)
                    .findFirst()
                    .orElseThrow();

            assertEquals(threadId, started.compaction().threadId());
            assertEquals(threadId, completed.compaction().threadId());
            assertEquals(response.compaction().compactionId(), completed.compaction().compactionId());
            assertNotNull(response.threadMemory());
            assertTrue(response.compaction().completed());
        }
    }

    @Test
    void threadListFiltersAndReadIncludeTurnsFlowThroughAppServerContract() throws Exception {
        CodexAppServer appServer = appServer(new NoOpTurnExecutor());

        try (CodexAppServerSession session = initializedSession(appServer)) {
            ThreadId threadId = session.threadStart(new ThreadStartParams("Alpha thread")).thread().threadId();
            session.turnStart(new TurnStartParams(threadId, "Inspect transport"));
            session.threadStart(new ThreadStartParams("Beta thread"));

            var filtered = session.threadList(new ThreadListParams(
                    null,
                    null,
                    null,
                    null,
                    null,
                    Boolean.FALSE,
                    null,
                    "inspect"));
            assertEquals(1, filtered.threads().size());
            assertEquals(threadId, filtered.threads().get(0).threadId());
            assertNull(filtered.nextCursor());

            var metadataOnly = session.threadRead(new ThreadReadParams(threadId, false));
            assertTrue(metadataOnly.turns().isEmpty());
            assertNull(metadataOnly.threadMemory());
            assertNull(metadataOnly.reconstructedContext());
            assertEquals(threadId, metadataOnly.treeRootThreadId());
            assertTrue(metadataOnly.relatedThreads().isEmpty());

            var withTurns = session.threadRead(new ThreadReadParams(threadId, true));
            assertEquals(1, withTurns.turns().size());
            assertNotNull(withTurns.threadMemory());
            assertNotNull(withTurns.reconstructedContext());
            assertEquals(threadId, withTurns.treeRootThreadId());
            assertTrue(withTurns.relatedThreads().isEmpty());

            var loaded = session.threadLoadedList(new ThreadLoadedListParams());
            assertTrue(loaded.data().contains(threadId));
        }
    }

    @Test
    void threadResumeBackfillsSubAgentSubscriptionsAndThreadReadReturnsTreeNavigation() throws Exception {
        ConversationStore conversationStore = new InMemoryConversationStore();
        DefaultCodexRuntimeGateway runtimeGateway = new DefaultCodexRuntimeGateway(
                conversationStore,
                new NoOpTurnExecutor(),
                new NoOpContextManager(),
                new NoOpThreadContextReconstructionService(),
                new NoOpSkillService());
        ThreadId parentThreadId = runtimeGateway.threadStart("Parent thread").threadId();
        ThreadId childThreadId = runtimeGateway.spawnAgent(new AgentSpawnRequest(
                parentThreadId,
                "root/worker-1",
                "Investigate the failing tests",
                "worker-1",
                "worker",
                null,
                null,
                null,
                null)).threadId();

        CodexAppServer appServer = new InProcessCodexAppServer(runtimeGateway);
        BlockingQueue<AppServerNotification> notifications = new LinkedBlockingQueue<>();

        try (CodexAppServerSession session = initializedSession(appServer);
             AutoCloseable ignored = session.subscribe(notifications::add)) {
            session.threadResume(new ThreadResumeParams(parentThreadId));

            var parentRead = session.threadRead(new ThreadReadParams(parentThreadId, false));
            assertEquals(parentThreadId, parentRead.treeRootThreadId());
            assertTrue(parentRead.relatedThreads().stream().anyMatch(summary -> summary.threadId().equals(childThreadId)));

            var childRead = session.threadRead(new ThreadReadParams(childThreadId, false));
            assertEquals(parentThreadId, childRead.treeRootThreadId());
            assertTrue(childRead.relatedThreads().stream().anyMatch(summary -> summary.threadId().equals(parentThreadId)));

            assertTrue(session.threadLoadedList(new ThreadLoadedListParams()).data().contains(childThreadId));

            runtimeGateway.turnStart(childThreadId, "Follow up on the investigation");

            List<AppServerNotification> observed = awaitNotifications(notifications, 3);
            assertTrue(observed.stream().anyMatch(notification ->
                    notification instanceof TurnStartedNotification started
                            && started.turn().threadId().equals(childThreadId)));
            assertTrue(observed.stream().anyMatch(notification ->
                    notification instanceof TurnCompletedNotification completed
                            && completed.turn().threadId().equals(childThreadId)));
        }
    }

    @Test
    void threadArchiveUnarchiveAndRollbackFlowThroughAppServerContract() throws Exception {
        CodexAppServer appServer = appServer(new NoOpTurnExecutor());

        try (CodexAppServerSession session = initializedSession(appServer)) {
            ThreadId threadId = session.threadStart(new ThreadStartParams("Lifecycle thread")).thread().threadId();
            session.turnStart(new TurnStartParams(threadId, "Inspect repo"));
            session.turnStart(new TurnStartParams(threadId, "Run tests"));

            var archived = session.threadArchive(new ThreadArchiveParams(threadId)).thread();
            assertTrue(archived.archived());
            assertFalse(session.threadLoadedList(new ThreadLoadedListParams()).data().contains(threadId));
            assertTrue(session.threadList(new ThreadListParams(null, null, null, null, null, null, null, null)).threads().isEmpty());
            assertEquals(List.of(threadId),
                    session.threadList(new ThreadListParams(null, null, null, null, null, Boolean.TRUE, null, null)).threads()
                            .stream()
                            .map(ThreadSummary::threadId)
                            .toList());

            var unarchived = session.threadUnarchive(new ThreadUnarchiveParams(threadId)).thread();
            assertFalse(unarchived.archived());

            var rollback = session.threadRollback(new ThreadRollbackParams(threadId, 1));
            assertEquals(1, rollback.thread().turnCount());
            assertEquals(1, rollback.turns().size());
            assertEquals("Inspect repo", rollback.turns().get(0).userInput());
        }
    }

    @Test
    void threadListSupportsFilteringAndPaginationCursor() throws Exception {
        Instant base = Instant.parse("2026-04-01T00:00:00Z");
        ThreadSummary alpha = new ThreadSummary(
                new ThreadId("thread-alpha"),
                "Alpha thread",
                base.plusSeconds(10),
                base.plusSeconds(30),
                1,
                "Alpha preview",
                false,
                "openai",
                "gpt-5.4",
                ThreadStatus.NOT_LOADED,
                List.of(),
                "/tmp/threads/thread-alpha",
                "/workspace/a",
                ThreadSource.CLI,
                true,
                null,
                null,
                null,
                null);
        ThreadSummary beta = new ThreadSummary(
                new ThreadId("thread-beta"),
                "Beta thread",
                base.plusSeconds(20),
                base.plusSeconds(40),
                2,
                "Beta preview",
                false,
                "openai",
                "gpt-5.4",
                ThreadStatus.NOT_LOADED,
                List.of(),
                "/tmp/threads/thread-beta",
                "/workspace/a",
                ThreadSource.CLI,
                true,
                null,
                null,
                null,
                null);
        ThreadSummary archived = new ThreadSummary(
                new ThreadId("thread-archived"),
                "Archived thread",
                base.plusSeconds(30),
                base.plusSeconds(50),
                3,
                "Archived alpha preview",
                false,
                "anthropic",
                "claude-sonnet",
                ThreadStatus.NOT_LOADED,
                List.of(),
                "/tmp/threads/thread-archived",
                "/workspace/b",
                ThreadSource.SUB_AGENT,
                true,
                base.plusSeconds(60),
                null,
                null,
                null);
        CodexRuntimeGateway gateway = new StubThreadListRuntimeGateway(List.of(alpha, beta, archived));
        CodexAppServer appServer = appServer(gateway);

        try (CodexAppServerSession session = initializedSession(appServer)) {
            var filtered = session.threadList(new ThreadListParams(
                    null,
                    10,
                    ThreadSortKey.CREATED_AT,
                    List.of("openai"),
                    List.of(ThreadSourceKind.CLI),
                    Boolean.FALSE,
                    "/workspace/a",
                    "alpha"));
            assertEquals(1, filtered.threads().size());
            assertEquals(alpha.threadId(), filtered.threads().get(0).threadId());
            assertNull(filtered.nextCursor());

            var firstPage = session.threadList(new ThreadListParams(
                    null,
                    1,
                    ThreadSortKey.CREATED_AT,
                    null,
                    List.of(ThreadSourceKind.CLI),
                    Boolean.FALSE,
                    null,
                    null));
            assertEquals(1, firstPage.threads().size());
            assertEquals(beta.threadId(), firstPage.threads().get(0).threadId());
            assertEquals("1", firstPage.nextCursor());

            var secondPage = session.threadList(new ThreadListParams(
                    firstPage.nextCursor(),
                    1,
                    ThreadSortKey.CREATED_AT,
                    null,
                    List.of(ThreadSourceKind.CLI),
                    Boolean.FALSE,
                    null,
                    null));
            assertEquals(1, secondPage.threads().size());
            assertEquals(alpha.threadId(), secondPage.threads().get(0).threadId());
            assertNull(secondPage.nextCursor());
        }
    }

    @Test
    void threadReadDoesNotLoadPersistedThreadButThreadResumeDoes() throws Exception {
        ConversationStore store = new InMemoryConversationStore();
        ThreadId threadId = store.createThread("Persisted thread");
        CodexAppServer appServer = appServer(store, new NoOpTurnExecutor());

        try (CodexAppServerSession session = initializedSession(appServer)) {
            var metadataOnly = session.threadRead(new ThreadReadParams(threadId, false));
            assertEquals(threadId, metadataOnly.thread().threadId());

            var loadedBeforeResume = session.threadLoadedList(new ThreadLoadedListParams());
            assertFalse(loadedBeforeResume.data().contains(threadId));

            var resumed = session.threadResume(new ThreadResumeParams(threadId));
            assertEquals(threadId, resumed.thread().threadId());

            var loadedAfterResume = session.threadLoadedList(new ThreadLoadedListParams());
            assertTrue(loadedAfterResume.data().contains(threadId));
        }
    }

    @Test
    void sessionRejectsOperationalCallsBeforeInitialization() throws Exception {
        CodexAppServer appServer = appServer(new NoOpTurnExecutor());

        try (CodexAppServerSession session = appServer.connect()) {
            IllegalStateException exception = assertThrows(IllegalStateException.class, session::threadList);
            assertEquals("Not initialized", exception.getMessage());
        }
    }

    @Test
    void sessionRejectsRepeatedInitialize() throws Exception {
        CodexAppServer appServer = appServer(new NoOpTurnExecutor());

        try (CodexAppServerSession session = appServer.connect()) {
            session.initialize(defaultInitializeParams(List.of()));
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> session.initialize(defaultInitializeParams(List.of())));
            assertEquals("Already initialized", exception.getMessage());
        }
    }

    @Test
    void notificationOptOutSuppressesExactMethodMatchesOnly() throws Exception {
        CodexAppServer appServer = appServer(new NoOpTurnExecutor());
        BlockingQueue<AppServerNotification> notifications = new LinkedBlockingQueue<>();

        try (CodexAppServerSession session = initializedSession(appServer, List.of("thread/started"));
             AutoCloseable ignored = session.subscribe(notifications::add)) {
            ThreadId threadId = session.threadStart(new ThreadStartParams("App thread")).thread().threadId();
            session.threadCompactStart(new ThreadCompactStartParams(threadId));

            List<AppServerNotification> observed = awaitNotifications(notifications, 2);
            assertEquals(2, observed.size());
            assertTrue(observed.stream().anyMatch(ThreadCompactionStartedNotification.class::isInstance));
            assertTrue(observed.stream().anyMatch(ThreadCompactedNotification.class::isInstance));
            assertFalse(observed.stream().anyMatch(ThreadStartedNotification.class::isInstance));
        }
    }

    private CodexAppServer appServer(TurnExecutor turnExecutor) {
        return appServer(new InMemoryConversationStore(), turnExecutor);
    }

    private CodexAppServer appServer(CodexRuntimeGateway runtimeGateway) {
        return new InProcessCodexAppServer(runtimeGateway);
    }

    private CodexAppServer appServer(ConversationStore store, TurnExecutor turnExecutor) {
        SkillService skillService = new SkillService() {
            @Override
            public List<SkillMetadata> listSkills(boolean forceReload) {
                return List.of(new SkillMetadata("reviewer", "Review code", "Review code", "/tmp/reviewer/SKILL.md", SkillScope.USER, true));
            }

            @Override
            public List<ResolvedSkill> resolveSkills(String input, boolean forceReload) {
                return List.of();
            }
        };
        ContextManager contextManager = new ContextManager() {
            @Override
            public Optional<ThreadMemory> latestThreadMemory(ThreadId threadId) {
                return Optional.of(new ThreadMemory("memory-1", threadId, "Compacted earlier thread context.", List.of(), 0, Instant.now()));
            }

            @Override
            public ThreadMemory compactThread(ThreadId threadId) {
                return latestThreadMemory(threadId).orElseThrow();
            }
        };
        ThreadContextReconstructionService reconstructionService = threadId -> new ReconstructedThreadContext(
                threadId,
                contextManager.latestThreadMemory(threadId).orElse(null),
                List.of(),
                List.of(),
                List.of(),
                Instant.now());
        return new InProcessCodexAppServer(new DefaultCodexRuntimeGateway(store, turnExecutor, contextManager, reconstructionService, skillService));
    }

    private static final class StubThreadListRuntimeGateway implements CodexRuntimeGateway {

        private final List<ThreadSummary> threads;

        private StubThreadListRuntimeGateway(List<ThreadSummary> threads) {
            this.threads = List.copyOf(threads);
        }

        @Override
        public ThreadSummary threadStart(String title) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public ThreadSummary threadResume(ThreadId threadId) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public List<ThreadSummary> listThreads() {
            return threads;
        }

        @Override
        public List<ConversationTurn> turns(ThreadId threadId) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public ConversationTurn turn(ThreadId threadId, TurnId turnId) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public ReconstructedThreadContext reconstructThreadContext(ThreadId threadId) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public Optional<ThreadMemory> latestThreadMemory(ThreadId threadId) {
            return Optional.empty();
        }

        @Override
        public ThreadMemory compactThread(ThreadId threadId) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public List<SkillMetadata> listSkills(boolean forceReload) {
            return List.of();
        }

        @Override
        public org.dean.codex.protocol.runtime.RuntimeTurn turnStart(ThreadId threadId, String input) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public org.dean.codex.protocol.runtime.RuntimeTurn turnResume(ThreadId threadId, TurnId turnId) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public boolean turnSteer(ThreadId threadId, TurnId turnId, String input) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public boolean turnInterrupt(ThreadId threadId, TurnId turnId) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public AutoCloseable subscribe(ThreadId threadId, Consumer<org.dean.codex.protocol.runtime.RuntimeNotification> listener) {
            throw new UnsupportedOperationException("Not used in this test");
        }
    }

    private CodexAppServerSession initializedSession(CodexAppServer appServer) {
        return initializedSession(appServer, List.of());
    }

    private CodexAppServerSession initializedSession(CodexAppServer appServer, List<String> optOutMethods) {
        CodexAppServerSession session = appServer.connect();
        session.initialize(defaultInitializeParams(optOutMethods));
        session.initialized(new InitializedNotification());
        return session;
    }

    private InitializeParams defaultInitializeParams(List<String> optOutMethods) {
        return new InitializeParams(
                new AppServerClientInfo("codex-java-test", "Codex Java Test", "1.0-SNAPSHOT"),
                new AppServerCapabilities(false, optOutMethods));
    }

    private List<AppServerNotification> awaitNotifications(BlockingQueue<AppServerNotification> notifications) throws InterruptedException {
        return awaitNotifications(notifications, 10);
    }

    private List<AppServerNotification> awaitNotifications(BlockingQueue<AppServerNotification> notifications, int maxNotifications) throws InterruptedException {
        List<AppServerNotification> observed = new ArrayList<>();
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadlineNanos && observed.size() < maxNotifications) {
            AppServerNotification notification = notifications.poll(100, TimeUnit.MILLISECONDS);
            if (notification == null) {
                continue;
            }
            observed.add(notification);
            if (notification instanceof TurnCompletedNotification) {
                break;
            }
        }
        return observed;
    }

    private static final class NoOpTurnExecutor implements TurnExecutor {
        @Override
        public CodexTurnResult executeTurn(ThreadId threadId, String input) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CodexTurnResult executeTurn(ThreadId threadId, TurnId turnId, String input, Consumer<TurnItem> itemConsumer, TurnControl turnControl) {
            return new CodexTurnResult(threadId, turnId, TurnStatus.COMPLETED, List.of(), "done");
        }

        @Override
        public CodexTurnResult resumeTurn(ThreadId threadId, TurnId turnId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class NoOpSkillService implements SkillService {
        @Override
        public List<SkillMetadata> listSkills(boolean forceReload) {
            return List.of();
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
            return new ThreadMemory("memory-0", threadId, "summary", List.of(), 0, Instant.now());
        }
    }

    private static final class NoOpThreadContextReconstructionService implements ThreadContextReconstructionService {
        @Override
        public ReconstructedThreadContext reconstruct(ThreadId threadId) {
            return new ReconstructedThreadContext(threadId, null, List.of(), List.of(), List.of(), Instant.now());
        }
    }

    private static final class SteeringTurnExecutor implements TurnExecutor {
        @Override
        public CodexTurnResult executeTurn(ThreadId threadId, String input) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CodexTurnResult executeTurn(ThreadId threadId, TurnId turnId, String input, Consumer<TurnItem> itemConsumer, TurnControl turnControl) {
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (System.nanoTime() < deadlineNanos) {
                List<String> steeringInputs = turnControl.drainSteeringInputs();
                if (!steeringInputs.isEmpty()) {
                    UserMessageItem steeringItem = new UserMessageItem(new ItemId("steer-1"), steeringInputs.get(0), Instant.now());
                    itemConsumer.accept(steeringItem);
                    return new CodexTurnResult(threadId, turnId, TurnStatus.COMPLETED, List.of(steeringItem), "done");
                }
                try {
                    Thread.sleep(10);
                }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return new CodexTurnResult(threadId, turnId, TurnStatus.COMPLETED, List.of(), "done");
        }

        @Override
        public CodexTurnResult resumeTurn(ThreadId threadId, TurnId turnId) {
            throw new UnsupportedOperationException();
        }
    }
}
