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
import org.dean.codex.protocol.appserver.ThreadStartedNotification;
import org.dean.codex.protocol.appserver.ThreadStartParams;
import org.dean.codex.protocol.appserver.TurnCompletedNotification;
import org.dean.codex.protocol.appserver.TurnStartParams;
import org.dean.codex.protocol.appserver.TurnStartedNotification;
import org.dean.codex.protocol.conversation.ThreadId;
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
        ConversationStore store = new InMemoryConversationStore();
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
