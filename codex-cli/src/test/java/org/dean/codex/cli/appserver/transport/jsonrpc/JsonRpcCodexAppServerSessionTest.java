package org.dean.codex.cli.appserver.transport.jsonrpc;

import org.dean.codex.core.agent.TurnControl;
import org.dean.codex.core.agent.TurnExecutor;
import org.dean.codex.core.appserver.CodexAppServer;
import org.dean.codex.core.context.ContextManager;
import org.dean.codex.core.context.ThreadContextReconstructionService;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.core.conversation.InMemoryConversationStore;
import org.dean.codex.core.skill.ResolvedSkill;
import org.dean.codex.core.skill.SkillService;
import org.dean.codex.protocol.appserver.AppServerCapabilities;
import org.dean.codex.protocol.appserver.AppServerClientInfo;
import org.dean.codex.protocol.appserver.AppServerNotification;
import org.dean.codex.protocol.appserver.InitializeParams;
import org.dean.codex.protocol.appserver.InitializedNotification;
import org.dean.codex.protocol.appserver.ThreadArchiveParams;
import org.dean.codex.protocol.appserver.ThreadForkParams;
import org.dean.codex.protocol.appserver.ThreadListParams;
import org.dean.codex.protocol.appserver.ThreadLoadedListParams;
import org.dean.codex.protocol.appserver.ThreadReadParams;
import org.dean.codex.protocol.appserver.ThreadRollbackParams;
import org.dean.codex.protocol.appserver.ThreadResumeParams;
import org.dean.codex.protocol.appserver.ThreadStartedNotification;
import org.dean.codex.protocol.appserver.ThreadStartParams;
import org.dean.codex.protocol.appserver.ThreadUnarchiveParams;
import org.dean.codex.protocol.appserver.TurnCompletedNotification;
import org.dean.codex.protocol.appserver.TurnStartParams;
import org.dean.codex.protocol.appserver.TurnStartedNotification;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.ThreadSource;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.context.ReconstructedThreadContext;
import org.dean.codex.protocol.context.ThreadMemory;
import org.dean.codex.protocol.event.CodexTurnResult;
import org.dean.codex.protocol.item.TurnItem;
import org.dean.codex.protocol.skill.SkillMetadata;
import org.dean.codex.runtime.springai.appserver.InProcessCodexAppServer;
import org.dean.codex.runtime.springai.appserver.transport.jsonrpc.JsonRpcAppServerDispatcher;
import org.dean.codex.runtime.springai.appserver.transport.jsonrpc.StdioJsonRpcAppServerHost;
import org.dean.codex.runtime.springai.runtime.DefaultCodexRuntimeGateway;
import org.junit.jupiter.api.Test;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonRpcCodexAppServerSessionTest {

    @Test
    void sessionHandlesInitializeThreadTurnAndNotificationsAcrossStdioTransport() throws Exception {
        CodexAppServer appServer = appServer();
        PipedOutputStream clientToServer = new PipedOutputStream();
        PipedInputStream serverInput = new PipedInputStream(clientToServer);
        PipedOutputStream serverToClient = new PipedOutputStream();
        PipedInputStream clientInput = new PipedInputStream(serverToClient);
        AtomicReference<Throwable> hostFailure = new AtomicReference<>();

        Thread hostThread = new Thread(() -> {
            try {
                new StdioJsonRpcAppServerHost(
                        new JsonRpcAppServerDispatcher(appServer),
                        serverInput,
                        serverToClient).run();
            }
            catch (Throwable throwable) {
                hostFailure.set(throwable);
            }
        }, "test-jsonrpc-appserver-host");
        hostThread.setDaemon(true);
        hostThread.start();

        BlockingQueue<AppServerNotification> notifications = new LinkedBlockingQueue<>();
        try (JsonRpcCodexAppServerSession session = new JsonRpcCodexAppServerSession(
                clientInput,
                clientToServer,
                () -> {
                    clientToServer.close();
                    hostThread.join(1_000);
                    clientInput.close();
                },
                Duration.ofSeconds(3));
             AutoCloseable ignored = session.subscribe(notifications::add)) {
            var initializeResponse = session.initialize(new InitializeParams(
                    new AppServerClientInfo("transport-client", "Transport Client", "1.0.0"),
                    new AppServerCapabilities(false, List.of())));
            assertEquals("transport-client", initializeResponse.userAgent());

            session.initialized(new InitializedNotification());
            ThreadId threadId = session.threadStart(new ThreadStartParams("Transport thread")).thread().threadId();
            TurnId turnId = session.turnStart(new TurnStartParams(threadId, "Inspect repo")).turn().turnId();
            assertNotNull(turnId);

            List<AppServerNotification> observed = awaitNotifications(notifications);
            assertTrue(observed.stream().anyMatch(notification ->
                    notification instanceof ThreadStartedNotification started && started.thread().threadId().equals(threadId)));
            assertTrue(observed.stream().anyMatch(notification ->
                    notification instanceof TurnStartedNotification started && started.turn().turnId().equals(turnId)));
            assertTrue(observed.stream().anyMatch(notification ->
                    notification instanceof TurnCompletedNotification completed && completed.turn().turnId().equals(turnId)));
        }

        hostThread.join(1_000);
        assertNull(hostFailure.get(), "Host failed: " + hostFailure.get());
    }

    @Test
    void sessionSupportsThreadListReadAndLoadedListAcrossStdioTransport() throws Exception {
        CodexAppServer appServer = appServer();
        PipedOutputStream clientToServer = new PipedOutputStream();
        PipedInputStream serverInput = new PipedInputStream(clientToServer);
        PipedOutputStream serverToClient = new PipedOutputStream();
        PipedInputStream clientInput = new PipedInputStream(serverToClient);
        AtomicReference<Throwable> hostFailure = new AtomicReference<>();

        Thread hostThread = new Thread(() -> {
            try {
                new StdioJsonRpcAppServerHost(
                        new JsonRpcAppServerDispatcher(appServer),
                        serverInput,
                        serverToClient).run();
            }
            catch (Throwable throwable) {
                hostFailure.set(throwable);
            }
        }, "test-jsonrpc-appserver-host-read");
        hostThread.setDaemon(true);
        hostThread.start();

        try (JsonRpcCodexAppServerSession session = new JsonRpcCodexAppServerSession(
                clientInput,
                clientToServer,
                () -> {
                    clientToServer.close();
                    hostThread.join(1_000);
                    clientInput.close();
                },
                Duration.ofSeconds(3))) {
            session.initialize(new InitializeParams(
                    new AppServerClientInfo("transport-client", "Transport Client", "1.0.0"),
                    new AppServerCapabilities(false, List.of())));
            session.initialized(new InitializedNotification());

            ThreadId threadId = session.threadStart(new ThreadStartParams("Transport thread")).thread().threadId();
            session.turnStart(new TurnStartParams(threadId, "Inspect repo"));

            var filtered = session.threadList(new ThreadListParams(null, null, null, null, null, Boolean.FALSE, null, "inspect"));
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
            assertNotNull(withTurns.reconstructedContext());
            assertEquals(threadId, withTurns.treeRootThreadId());
            assertTrue(withTurns.relatedThreads().isEmpty());

            var loaded = session.threadLoadedList(new ThreadLoadedListParams());
            assertTrue(loaded.data().contains(threadId));
        }

        hostThread.join(1_000);
        assertNull(hostFailure.get(), "Host failed: " + hostFailure.get());
    }

    @Test
    void sessionThreadReadDoesNotLoadPersistedThreadButResumeDoes() throws Exception {
        ConversationStore store = new InMemoryConversationStore();
        ThreadId threadId = store.createThread("Persisted thread");
        CodexAppServer appServer = appServer(store);
        PipedOutputStream clientToServer = new PipedOutputStream();
        PipedInputStream serverInput = new PipedInputStream(clientToServer);
        PipedOutputStream serverToClient = new PipedOutputStream();
        PipedInputStream clientInput = new PipedInputStream(serverToClient);
        AtomicReference<Throwable> hostFailure = new AtomicReference<>();

        Thread hostThread = new Thread(() -> {
            try {
                new StdioJsonRpcAppServerHost(
                        new JsonRpcAppServerDispatcher(appServer),
                        serverInput,
                        serverToClient).run();
            }
            catch (Throwable throwable) {
                hostFailure.set(throwable);
            }
        }, "test-jsonrpc-appserver-host-resume");
        hostThread.setDaemon(true);
        hostThread.start();

        try (JsonRpcCodexAppServerSession session = new JsonRpcCodexAppServerSession(
                clientInput,
                clientToServer,
                () -> {
                    clientToServer.close();
                    hostThread.join(1_000);
                    clientInput.close();
                },
                Duration.ofSeconds(3))) {
            session.initialize(new InitializeParams(
                    new AppServerClientInfo("transport-client", "Transport Client", "1.0.0"),
                    new AppServerCapabilities(false, List.of())));
            session.initialized(new InitializedNotification());

            var metadataOnly = session.threadRead(new ThreadReadParams(threadId, false));
            assertEquals(threadId, metadataOnly.thread().threadId());
            assertNull(metadataOnly.threadMemory());
            assertNull(metadataOnly.reconstructedContext());

            var loadedBeforeResume = session.threadLoadedList(new ThreadLoadedListParams());
            assertTrue(loadedBeforeResume.data().stream().noneMatch(threadId::equals));

            var resumed = session.threadResume(new ThreadResumeParams(threadId));
            assertEquals(threadId, resumed.thread().threadId());

            var loadedAfterResume = session.threadLoadedList(new ThreadLoadedListParams());
            assertTrue(loadedAfterResume.data().contains(threadId));
        }

        hostThread.join(1_000);
        assertNull(hostFailure.get(), "Host failed: " + hostFailure.get());
    }

    @Test
    void sessionThreadForkCopiesParentHistoryAndDiverges() throws Exception {
        CodexAppServer appServer = appServer();
        PipedOutputStream clientToServer = new PipedOutputStream();
        PipedInputStream serverInput = new PipedInputStream(clientToServer);
        PipedOutputStream serverToClient = new PipedOutputStream();
        PipedInputStream clientInput = new PipedInputStream(serverToClient);
        AtomicReference<Throwable> hostFailure = new AtomicReference<>();

        Thread hostThread = new Thread(() -> {
            try {
                new StdioJsonRpcAppServerHost(
                        new JsonRpcAppServerDispatcher(appServer),
                        serverInput,
                        serverToClient).run();
            }
            catch (Throwable throwable) {
                hostFailure.set(throwable);
            }
        }, "test-jsonrpc-appserver-host-fork");
        hostThread.setDaemon(true);
        hostThread.start();

        try (JsonRpcCodexAppServerSession session = new JsonRpcCodexAppServerSession(
                clientInput,
                clientToServer,
                () -> {
                    clientToServer.close();
                    hostThread.join(1_000);
                    clientInput.close();
                },
                Duration.ofSeconds(3))) {
            session.initialize(new InitializeParams(
                    new AppServerClientInfo("transport-client", "Transport Client", "1.0.0"),
                    new AppServerCapabilities(false, List.of())));
            session.initialized(new InitializedNotification());

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

            var forkedRead = session.threadRead(new ThreadReadParams(forked.threadId(), true));
            assertEquals(1, forkedRead.turns().size());

            session.turnStart(new TurnStartParams(forked.threadId(), "Write follow-up"));

            assertEquals(1, session.threadRead(new ThreadReadParams(parentThreadId, true)).turns().size());
            assertEquals(2, session.threadRead(new ThreadReadParams(forked.threadId(), true)).turns().size());
        }

        hostThread.join(1_000);
        assertNull(hostFailure.get(), "Host failed: " + hostFailure.get());
    }

    @Test
    void sessionSupportsThreadArchiveUnarchiveAndRollbackAcrossStdioTransport() throws Exception {
        CodexAppServer appServer = appServer();
        PipedOutputStream clientToServer = new PipedOutputStream();
        PipedInputStream serverInput = new PipedInputStream(clientToServer);
        PipedOutputStream serverToClient = new PipedOutputStream();
        PipedInputStream clientInput = new PipedInputStream(serverToClient);
        AtomicReference<Throwable> hostFailure = new AtomicReference<>();

        Thread hostThread = new Thread(() -> {
            try {
                new StdioJsonRpcAppServerHost(
                        new JsonRpcAppServerDispatcher(appServer),
                        serverInput,
                        serverToClient).run();
            }
            catch (Throwable throwable) {
                hostFailure.set(throwable);
            }
        }, "test-jsonrpc-appserver-host-archive-rollback");
        hostThread.setDaemon(true);
        hostThread.start();

        try (JsonRpcCodexAppServerSession session = new JsonRpcCodexAppServerSession(
                clientInput,
                clientToServer,
                () -> {
                    clientToServer.close();
                    hostThread.join(1_000);
                    clientInput.close();
                },
                Duration.ofSeconds(3))) {
            session.initialize(new InitializeParams(
                    new AppServerClientInfo("transport-client", "Transport Client", "1.0.0"),
                    new AppServerCapabilities(false, List.of())));
            session.initialized(new InitializedNotification());

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
                            .map(thread -> thread.threadId())
                            .toList());

            var unarchived = session.threadUnarchive(new ThreadUnarchiveParams(threadId)).thread();
            assertFalse(unarchived.archived());

            var rollback = session.threadRollback(new ThreadRollbackParams(threadId, 1));
            assertEquals(1, rollback.thread().turnCount());
            assertEquals(1, rollback.turns().size());
            assertEquals("Inspect repo", rollback.turns().get(0).userInput());
        }

        hostThread.join(1_000);
        assertNull(hostFailure.get(), "Host failed: " + hostFailure.get());
    }

    private List<AppServerNotification> awaitNotifications(BlockingQueue<AppServerNotification> notifications) throws InterruptedException {
        java.util.ArrayList<AppServerNotification> observed = new java.util.ArrayList<>();
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadlineNanos) {
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

    private CodexAppServer appServer() {
        return appServer(new InMemoryConversationStore());
    }

    private CodexAppServer appServer(ConversationStore store) {
        SkillService skillService = new SkillService() {
            @Override
            public List<SkillMetadata> listSkills(boolean forceReload) {
                return List.of();
            }

            @Override
            public List<ResolvedSkill> resolveSkills(String input, boolean forceReload) {
                return List.of();
            }
        };
        ContextManager contextManager = new ContextManager() {
            @Override
            public Optional<ThreadMemory> latestThreadMemory(ThreadId threadId) {
                return Optional.empty();
            }

            @Override
            public ThreadMemory compactThread(ThreadId threadId) {
                return new ThreadMemory("memory-1", threadId, "summary", List.of(), 0, Instant.now());
            }
        };
        ThreadContextReconstructionService reconstructionService = threadId -> new ReconstructedThreadContext(
                threadId,
                null,
                List.of(),
                List.of(),
                List.of(),
                Instant.now());

        return new InProcessCodexAppServer(
                new DefaultCodexRuntimeGateway(store, new NoOpTurnExecutor(), contextManager, reconstructionService, skillService));
    }

    private static final class NoOpTurnExecutor implements TurnExecutor {
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
            return new CodexTurnResult(threadId, turnId, TurnStatus.COMPLETED, List.of(), "done");
        }

        @Override
        public CodexTurnResult resumeTurn(ThreadId threadId, TurnId turnId) {
            throw new UnsupportedOperationException();
        }
    }
}
