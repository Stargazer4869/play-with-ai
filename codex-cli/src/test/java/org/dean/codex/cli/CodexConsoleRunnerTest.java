package org.dean.codex.cli;

import org.dean.codex.core.approval.CommandApprovalService;
import org.dean.codex.core.appserver.CodexAppServer;
import org.dean.codex.core.appserver.CodexAppServerSession;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.core.conversation.InMemoryConversationStore;
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
import org.dean.codex.protocol.appserver.ThreadStartParams;
import org.dean.codex.protocol.appserver.ThreadStartResponse;
import org.dean.codex.protocol.appserver.ThreadStartedNotification;
import org.dean.codex.protocol.appserver.ThreadUnarchiveParams;
import org.dean.codex.protocol.appserver.ThreadUnarchiveResponse;
import org.dean.codex.protocol.appserver.TurnCompletedNotification;
import org.dean.codex.protocol.appserver.TurnInterruptParams;
import org.dean.codex.protocol.appserver.TurnInterruptResponse;
import org.dean.codex.protocol.appserver.TurnResumeParams;
import org.dean.codex.protocol.appserver.TurnResumeResponse;
import org.dean.codex.protocol.appserver.TurnStartParams;
import org.dean.codex.protocol.appserver.TurnStartResponse;
import org.dean.codex.protocol.appserver.TurnSteerParams;
import org.dean.codex.protocol.appserver.TurnSteerResponse;
import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.approval.ApprovalId;
import org.dean.codex.protocol.approval.ApprovalStatus;
import org.dean.codex.protocol.approval.CommandApprovalRequest;
import org.dean.codex.protocol.agent.AgentStatus;
import org.dean.codex.protocol.context.ReconstructedThreadContext;
import org.dean.codex.protocol.context.ThreadMemory;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.ThreadStatus;
import org.dean.codex.protocol.conversation.ThreadSummary;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.runtime.RuntimeTurn;
import org.dean.codex.protocol.skill.SkillMetadata;
import org.dean.codex.protocol.skill.SkillScope;
import org.dean.codex.protocol.tool.CommandApprovalDecision;
import org.dean.codex.protocol.tool.ShellCommandResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexConsoleRunnerTest {

    @Test
    void createsNewThreadFromConsoleCommand() throws Exception {
        StubAppServer runtime = new StubAppServer();
        CodexConsoleRunner runner = new CodexConsoleRunner(runtime, new StubApprovalService());
        ThreadId originalThread = runner.getActiveThreadIdForTest();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            assertTrue(runner.handleConsoleCommand(":new"));
        }
        finally {
            System.setOut(originalOut);
        }

        assertNotEquals(originalThread, runner.getActiveThreadIdForTest());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Started new thread"));
    }

    @Test
    void listsThreadsFromConsoleCommand() throws Exception {
        CodexConsoleRunner runner = new CodexConsoleRunner(new StubAppServer(), new StubApprovalService());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            assertTrue(runner.handleConsoleCommand(":threads"));
        }
        finally {
            System.setOut(originalOut);
        }

        String console = output.toString(StandardCharsets.UTF_8);
        assertTrue(console.contains("Thread 1"));
        assertTrue(console.contains("turns=0"));
    }

    @Test
    void ignoresNonCommandInput() {
        CodexConsoleRunner runner = new CodexConsoleRunner(new StubAppServer(), new StubApprovalService());

        assertFalse(runner.handleConsoleCommand("hello there"));
    }

    @Test
    void listsApprovalsFromConsoleCommand() throws Exception {
        CodexConsoleRunner runner = new CodexConsoleRunner(new StubAppServer(), new StubApprovalService());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            assertTrue(runner.handleConsoleCommand(":approvals"));
        }
        finally {
            System.setOut(originalOut);
        }

        String console = output.toString(StandardCharsets.UTF_8);
        assertTrue(console.contains("pending command"));
        assertTrue(console.contains("PENDING"));
    }

    @Test
    void listsSkillsFromConsoleCommand() throws Exception {
        CodexConsoleRunner runner = new CodexConsoleRunner(new StubAppServer(), new StubApprovalService());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            assertTrue(runner.handleConsoleCommand(":skills"));
        }
        finally {
            System.setOut(originalOut);
        }

        String console = output.toString(StandardCharsets.UTF_8);
        assertTrue(console.contains("reviewer"));
        assertTrue(console.contains("mention `$reviewer`"));
    }

    @Test
    void applicationConfigImportsSharedRuntimeDefaults() throws Exception {
        try (var inputStream = CodexConsoleRunnerTest.class.getResourceAsStream("/application.yml")) {
            assertNotNull(inputStream);
            String config = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(config.contains("import: optional:classpath:codex-runtime-defaults.yml"));
            assertTrue(config.contains("web-application-type: none"));
        }
    }

    @Test
    void approveCommandResumesTurn() throws Exception {
        CodexConsoleRunner runner = new CodexConsoleRunner(new StubAppServer(), new StubApprovalService());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            assertTrue(runner.handleConsoleCommand(":approve approval-"));
        }
        finally {
            System.setOut(originalOut);
        }

        String console = output.toString(StandardCharsets.UTF_8);
        assertTrue(console.contains("Approved command"));
        assertTrue(console.contains("resumed turn"));
    }

    @Test
    void compactCommandPrintsCompactionLifecycleAndCompatibilitySnapshot() throws Exception {
        CodexConsoleRunner runner = new CodexConsoleRunner(new StubAppServer(), new StubApprovalService());

        String console = captureConsole(() -> assertTrue(runner.handleConsoleCommand(":compact")));
        assertTrue(console.contains("[compaction] started"));
        assertTrue(console.contains("[compaction] completed"));
        assertTrue(console.contains("[compaction] response"));
        assertTrue(console.contains("[memory] compatibility snapshot"));
    }

    @Test
    void resumeCommandSwitchesActiveThread() throws Exception {
        StubAppServer runtime = new StubAppServer();
        CodexConsoleRunner runner = new CodexConsoleRunner(runtime, new StubApprovalService());

        String console = captureConsole(() -> assertTrue(runner.handleConsoleCommand(":resume " + shortId(runtime.subagentThreadId()))));

        assertEquals(runtime.subagentThreadId(), runner.getActiveThreadIdForTest());
        assertTrue(console.contains("Switched to thread"));
    }

    @Test
    void forkCommandCreatesThreadAndSwitchesActiveThread() throws Exception {
        StubAppServer runtime = new StubAppServer();
        CodexConsoleRunner runner = new CodexConsoleRunner(runtime, new StubApprovalService());
        ThreadId originalThread = runner.getActiveThreadIdForTest();

        String console = captureConsole(() -> assertTrue(runner.handleConsoleCommand(":fork worker review")));

        assertNotEquals(originalThread, runner.getActiveThreadIdForTest());
        assertTrue(console.contains("Forked thread"));
        assertTrue(console.contains("Switched to thread"));
    }

    @Test
    void archiveCommandArchivesActiveThreadAndSwitchesContext() throws Exception {
        StubAppServer runtime = new StubAppServer();
        CodexConsoleRunner runner = new CodexConsoleRunner(runtime, new StubApprovalService());
        ThreadId originalThread = runner.getActiveThreadIdForTest();

        String console = captureConsole(() -> assertTrue(runner.handleConsoleCommand(":archive")));

        assertNotEquals(originalThread, runner.getActiveThreadIdForTest());
        assertTrue(console.contains("Archived thread"));
        assertTrue(console.contains("Switched to thread"));
    }

    @Test
    void rollbackCommandTrimsThreadHistory() throws Exception {
        StubAppServer runtime = new StubAppServer();
        CodexConsoleRunner runner = new CodexConsoleRunner(runtime, new StubApprovalService());
        runtime.addCompletedTurn(runner.getActiveThreadIdForTest(), "Inspect repo");

        String console = captureConsole(() -> assertTrue(runner.handleConsoleCommand(":rollback 1")));

        assertEquals(0, runtime.turnCount(runner.getActiveThreadIdForTest()));
        assertTrue(console.contains("Rolled back 1 turn(s)"));
        assertTrue(console.contains("Remaining turns=0"));
    }

    @Test
    void subagentsCommandPrintsThreadTree() throws Exception {
        StubAppServer runtime = new StubAppServer();
        CodexConsoleRunner runner = new CodexConsoleRunner(runtime, new StubApprovalService());

        String console = captureConsole(() -> assertTrue(runner.handleConsoleCommand(":subagents")));

        assertTrue(console.contains("Thread tree rooted at"));
        assertTrue(console.contains("Worker thread"));
        assertTrue(console.contains("role=explorer"));
    }

    @Test
    void agentUseCommandSwitchesToSubagent() throws Exception {
        StubAppServer runtime = new StubAppServer();
        CodexConsoleRunner runner = new CodexConsoleRunner(runtime, new StubApprovalService());

        String console = captureConsole(() -> assertTrue(runner.handleConsoleCommand(":agent use " + shortId(runtime.subagentThreadId()))));

        assertEquals(runtime.subagentThreadId(), runner.getActiveThreadIdForTest());
        assertTrue(console.contains("Switched to thread"));
    }

    private static String captureConsole(ThrowingRunnable action) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            action.run();
        }
        finally {
            System.setOut(originalOut);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String shortId(ThreadId threadId) {
        String value = threadId.value();
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    private static final class StubAppServer implements CodexAppServer {

        private final ConversationStore store = new InMemoryConversationStore();
        private final Set<ThreadId> loadedThreadIds = new LinkedHashSet<>();
        private final ThreadId rootThreadId;
        private final ThreadId subagentThreadId;

        private StubAppServer() {
            this.rootThreadId = store.createThread("Thread 1");
            this.subagentThreadId = store.createThread("Worker thread");
            loadedThreadIds.add(rootThreadId);
            loadedThreadIds.add(subagentThreadId);
            store.updateAgentThread(subagentThreadId, rootThreadId, 1, null, "worker", "explorer", "src/demo");
        }

        private ThreadId subagentThreadId() {
            return subagentThreadId;
        }

        private void addCompletedTurn(ThreadId threadId, String input) {
            Instant now = Instant.now();
            TurnId turnId = store.startTurn(threadId, input, now);
            store.completeTurn(threadId, turnId, TurnStatus.COMPLETED, "handled: " + input, now.plusSeconds(1));
        }

        private int turnCount(ThreadId threadId) {
            return store.turns(threadId).size();
        }

        @Override
        public CodexAppServerSession connect() {
            return new StubSession();
        }

        private ThreadSummary requireThread(ThreadId threadId) {
            return store.listThreads().stream()
                    .filter(summary -> summary.threadId().equals(threadId))
                    .findFirst()
                    .orElseThrow();
        }

        private ThreadSummary runtimeSummary(ThreadId threadId) {
            return runtimeSummary(requireThread(threadId));
        }

        private ThreadSummary runtimeSummary(ThreadSummary thread) {
            boolean loaded = loadedThreadIds.contains(thread.threadId()) && !thread.archived();
            AgentStatus agentStatus = thread.parentThreadId() == null
                    ? null
                    : loaded ? AgentStatus.IDLE : thread.agentClosedAt() == null ? AgentStatus.PENDING_INIT : AgentStatus.SHUTDOWN;
            return thread.withRuntime(loaded ? ThreadStatus.IDLE : ThreadStatus.NOT_LOADED, List.of(), agentStatus);
        }

        private ThreadId treeRoot(ThreadId threadId) {
            ThreadSummary current = requireThread(threadId);
            while (current.parentThreadId() != null) {
                current = requireThread(current.parentThreadId());
            }
            return current.threadId();
        }

        private List<ThreadSummary> relatedThreads(ThreadId threadId) {
            ThreadId root = treeRoot(threadId);
            return store.listThreads().stream()
                    .map(this::runtimeSummary)
                    .filter(summary -> treeRoot(summary.threadId()).equals(root))
                    .filter(summary -> !summary.threadId().equals(threadId))
                    .toList();
        }

        private final class StubSession implements CodexAppServerSession {

            private final CopyOnWriteArrayList<Consumer<AppServerNotification>> listeners = new CopyOnWriteArrayList<>();
            private boolean initializeCalled;
            private boolean initializedAcknowledged;

            @Override
            public InitializeResponse initialize(InitializeParams params) {
                if (initializeCalled) {
                    throw new IllegalStateException("Already initialized");
                }
                initializeCalled = true;
                return new InitializeResponse(
                        params == null || params.clientInfo() == null ? "codex-java-test" : params.clientInfo().name(),
                        "/tmp/.codex-java",
                        "desktop",
                        "test");
            }

            @Override
            public void initialized(InitializedNotification notification) {
                if (!initializeCalled) {
                    throw new IllegalStateException("Not initialized");
                }
                initializedAcknowledged = true;
            }

            @Override
            public ThreadStartResponse threadStart(ThreadStartParams params) {
                ensureReady();
                String title = params == null ? "" : params.title();
                ThreadId threadId = store.createThread(title);
                loadedThreadIds.add(threadId);
                var thread = runtimeSummary(threadId);
                publish(threadId, new ThreadStartedNotification(thread));
                return new ThreadStartResponse(thread);
            }

            @Override
            public ThreadResumeResponse threadResume(ThreadResumeParams params) {
                ensureReady();
                ThreadId threadId = params.threadId();
                if (requireThread(threadId).archived()) {
                    throw new IllegalArgumentException("Archived thread id: " + threadId.value());
                }
                loadedThreadIds.add(threadId);
                return new ThreadResumeResponse(runtimeSummary(threadId));
            }

            @Override
            public ThreadListResponse threadList(ThreadListParams params) {
                ensureReady();
                boolean explicitArchivedFilter = params != null && params.archived() != null;
                boolean archived = explicitArchivedFilter && params.archived();
                List<ThreadSummary> threads = store.listThreads().stream()
                        .map(StubAppServer.this::runtimeSummary)
                        .filter(thread -> explicitArchivedFilter ? thread.archived() == archived : !thread.archived())
                        .toList();
                return new ThreadListResponse(threads, null);
            }

            @Override
            public ThreadLoadedListResponse threadLoadedList(ThreadLoadedListParams params) {
                ensureReady();
                return new ThreadLoadedListResponse(
                        store.listThreads().stream()
                                .map(StubAppServer.this::runtimeSummary)
                                .filter(ThreadSummary::loaded)
                                .map(ThreadSummary::threadId)
                                .toList(),
                        null);
            }

            @Override
            public ThreadReadResponse threadRead(ThreadReadParams params) {
                ensureReady();
                ThreadId threadId = params.threadId();
                List<ConversationTurn> turns = params.includeTurns() ? store.turns(threadId) : List.of();
                ThreadMemory threadMemory = params.includeTurns() ? latestThreadMemory(threadId) : null;
                ReconstructedThreadContext reconstructedContext = params.includeTurns()
                        ? new ReconstructedThreadContext(threadId, threadMemory, List.of(), turns, List.of(), Instant.now())
                        : null;
                return new ThreadReadResponse(
                        runtimeSummary(threadId),
                        turns,
                        threadMemory,
                        reconstructedContext,
                        treeRoot(threadId),
                        relatedThreads(threadId));
            }

            @Override
            public ThreadForkResponse threadFork(ThreadForkParams params) {
                ensureReady();
                ThreadId threadId = store.forkThread(params);
                loadedThreadIds.add(threadId);
                return new ThreadForkResponse(runtimeSummary(threadId));
            }

            @Override
            public ThreadArchiveResponse threadArchive(ThreadArchiveParams params) {
                ensureReady();
                loadedThreadIds.remove(params.threadId());
                return new ThreadArchiveResponse(runtimeSummary(store.archiveThread(params.threadId())));
            }

            @Override
            public ThreadUnarchiveResponse threadUnarchive(ThreadUnarchiveParams params) {
                ensureReady();
                return new ThreadUnarchiveResponse(runtimeSummary(store.unarchiveThread(params.threadId())));
            }

            @Override
            public ThreadRollbackResponse threadRollback(ThreadRollbackParams params) {
                ensureReady();
                return new ThreadRollbackResponse(
                        runtimeSummary(store.rollbackThread(params.threadId(), params.numTurns())),
                        store.turns(params.threadId()));
            }

            @Override
            public ThreadCompactStartResponse threadCompactStart(ThreadCompactStartParams params) {
                ensureReady();
                ThreadMemory threadMemory = latestThreadMemory(params.threadId());
                ThreadCompaction started = new ThreadCompaction(
                        "comp-1",
                        params.threadId(),
                        List.of(),
                        0,
                        "",
                        Instant.parse("2026-03-31T00:00:00Z"),
                        null);
                ThreadCompaction completed = new ThreadCompaction(
                        "comp-1",
                        params.threadId(),
                        List.of(),
                        threadMemory.compactedTurnCount(),
                        threadMemory.summary(),
                        Instant.parse("2026-03-31T00:00:00Z"),
                        threadMemory.createdAt());
                Thread notificationThread = new Thread(() -> {
                    try {
                        Thread.sleep(10);
                    }
                    catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    publish(params.threadId(), new ThreadCompactionStartedNotification(started));
                    publish(params.threadId(), new ThreadCompactedNotification(completed));
                }, "stub-compaction-notifications");
                notificationThread.setDaemon(true);
                notificationThread.start();
                return new ThreadCompactStartResponse(completed, threadMemory);
            }

            @Override
            public SkillsListResponse skillsList(SkillsListParams params) {
                ensureReady();
                return new SkillsListResponse(List.of(new SkillMetadata(
                        "reviewer",
                        "Review code for bugs and regressions.",
                        "Review code for bugs and regressions.",
                        "/tmp/skills/reviewer/SKILL.md",
                        SkillScope.USER,
                        true)));
            }

            @Override
            public TurnStartResponse turnStart(TurnStartParams params) {
                ensureReady();
                ThreadId threadId = params.threadId();
                String input = params.input();
                Instant now = Instant.now();
                TurnId turnId = store.startTurn(threadId, input, now);
                store.completeTurn(threadId, turnId, TurnStatus.COMPLETED, "handled: " + input, now.plusSeconds(1));
                publish(threadId, new TurnCompletedNotification(
                        new RuntimeTurn(threadId, turnId, TurnStatus.COMPLETED, now, now.plusSeconds(1)),
                        "handled: " + input));
                return new TurnStartResponse(new RuntimeTurn(threadId, turnId, TurnStatus.RUNNING, now, null));
            }

            @Override
            public TurnResumeResponse turnResume(TurnResumeParams params) {
                ensureReady();
                ThreadId threadId = params.threadId();
                TurnId turnId = params.turnId();
                Instant now = Instant.now();
                if (!store.exists(threadId)) {
                    throw new IllegalArgumentException("Unknown thread id: " + threadId.value());
                }
                ConversationTurn existingTurn;
                try {
                    existingTurn = store.turn(threadId, turnId);
                }
                catch (IllegalArgumentException ignored) {
                    TurnId startedTurnId = store.startTurn(threadId, "resume request", now);
                    existingTurn = store.turn(threadId, startedTurnId);
                    turnId = startedTurnId;
                }
                store.completeTurn(threadId, turnId, TurnStatus.COMPLETED, "resumed turn", now.plusSeconds(1));
                publish(threadId, new TurnCompletedNotification(
                        new RuntimeTurn(threadId, turnId, TurnStatus.COMPLETED, existingTurn.startedAt(), now.plusSeconds(1)),
                        "resumed turn"));
                return new TurnResumeResponse(new RuntimeTurn(threadId, turnId, TurnStatus.RUNNING, existingTurn.startedAt(), null));
            }

            @Override
            public TurnInterruptResponse turnInterrupt(TurnInterruptParams params) {
                ensureReady();
                return new TurnInterruptResponse(params.turnId(), true);
            }

            @Override
            public TurnSteerResponse turnSteer(TurnSteerParams params) {
                ensureReady();
                return new TurnSteerResponse(params.turnId(), true);
            }

            @Override
            public AutoCloseable subscribe(Consumer<AppServerNotification> listener) {
                ensureReady();
                listeners.add(listener);
                return () -> listeners.remove(listener);
            }

            @Override
            public void close() {
                listeners.clear();
            }

            private void ensureReady() {
                if (!initializeCalled || !initializedAcknowledged) {
                    throw new IllegalStateException("Not initialized");
                }
            }

            private void publish(ThreadId threadId, AppServerNotification notification) {
                for (Consumer<AppServerNotification> listener : listeners) {
                    listener.accept(notification);
                }
            }

            private ThreadMemory latestThreadMemory(ThreadId threadId) {
                return new ThreadMemory(
                        "memory-1",
                        threadId,
                        "Compacted earlier thread context:\n- USER: Inspect repo\n  ASSISTANT: handled: Inspect repo",
                        List.of(),
                        1,
                        Instant.now());
            }
        }
    }

    private static final class StubApprovalService implements CommandApprovalService {

        @Override
        public CommandApprovalRequest requestApproval(ThreadId threadId, TurnId turnId, String command, String workingDirectory, String reason) {
            return sampleApproval(threadId);
        }

        @Override
        public List<CommandApprovalRequest> approvals(ThreadId threadId) {
            return List.of(sampleApproval(threadId));
        }

        @Override
        public CommandApprovalRequest approve(ThreadId threadId, String approvalIdPrefix) {
            return new CommandApprovalRequest(
                    new ApprovalId("approval-1234"),
                    threadId,
                    new TurnId("turn-1"),
                    "pending command",
                    "/tmp/workspace",
                    "Needs approval",
                    ApprovalStatus.APPROVED,
                    Instant.now(),
                    Instant.now(),
                    "Approved from CLI.",
                    new ShellCommandResult(true, "pending command", 0, "ok", "", false, "/tmp/workspace", true,
                            CommandApprovalDecision.ALLOW, "Approved", ""));
        }

        @Override
        public CommandApprovalRequest reject(ThreadId threadId, String approvalIdPrefix, String reason) {
            return new CommandApprovalRequest(
                    new ApprovalId("approval-1234"),
                    threadId,
                    new TurnId("turn-1"),
                    "pending command",
                    "/tmp/workspace",
                    "Needs approval",
                    ApprovalStatus.REJECTED,
                    Instant.now(),
                    Instant.now(),
                    reason,
                    null);
        }

        private CommandApprovalRequest sampleApproval(ThreadId threadId) {
            return new CommandApprovalRequest(
                    new ApprovalId("approval-1234"),
                    threadId,
                    new TurnId("turn-1"),
                    "pending command",
                    "/tmp/workspace",
                    "Needs approval",
                    ApprovalStatus.PENDING,
                    Instant.now(),
                    Instant.now(),
                    "",
                    null);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
