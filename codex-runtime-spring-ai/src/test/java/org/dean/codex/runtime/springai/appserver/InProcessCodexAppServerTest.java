package org.dean.codex.runtime.springai.appserver;

import org.dean.codex.core.agent.TurnControl;
import org.dean.codex.core.agent.TurnExecutor;
import org.dean.codex.core.appserver.CodexAppServer;
import org.dean.codex.core.appserver.CodexAppServerSession;
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
import org.dean.codex.protocol.appserver.SkillsListParams;
import org.dean.codex.protocol.appserver.ThreadCompaction;
import org.dean.codex.protocol.appserver.ThreadCompactStartParams;
import org.dean.codex.protocol.appserver.ThreadCompactionStartedNotification;
import org.dean.codex.protocol.appserver.ThreadCompactedNotification;
import org.dean.codex.protocol.appserver.ThreadStartParams;
import org.dean.codex.protocol.appserver.ThreadStartedNotification;
import org.dean.codex.protocol.appserver.TurnCompletedNotification;
import org.dean.codex.protocol.appserver.TurnItemNotification;
import org.dean.codex.protocol.appserver.TurnStartParams;
import org.dean.codex.protocol.appserver.TurnStartedNotification;
import org.dean.codex.protocol.appserver.TurnSteerParams;
import org.dean.codex.protocol.conversation.ItemId;
import org.dean.codex.protocol.conversation.ThreadId;
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
        ConversationStore store = new InMemoryConversationStore();
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
