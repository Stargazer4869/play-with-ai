package org.dean.codex.cli;

import org.dean.codex.core.approval.CommandApprovalService;
import org.dean.codex.core.agent.TurnExecutor;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.core.conversation.InMemoryConversationStore;
import org.dean.codex.protocol.approval.ApprovalId;
import org.dean.codex.protocol.approval.ApprovalStatus;
import org.dean.codex.protocol.approval.CommandApprovalRequest;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.event.CodexTurnResult;
import org.dean.codex.protocol.tool.CommandApprovalDecision;
import org.dean.codex.protocol.tool.ShellCommandResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexConsoleRunnerTest {

    @Test
    void createsNewThreadFromConsoleCommand() throws Exception {
        ConversationStore store = new InMemoryConversationStore();
        CodexConsoleRunner runner = new CodexConsoleRunner(new StubTurnExecutor(), store, new StubApprovalService());
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
        ConversationStore store = new InMemoryConversationStore();
        CodexConsoleRunner runner = new CodexConsoleRunner(new StubTurnExecutor(), store, new StubApprovalService());

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
        CodexConsoleRunner runner = new CodexConsoleRunner(new StubTurnExecutor(), new InMemoryConversationStore(), new StubApprovalService());

        assertFalse(runner.handleConsoleCommand("hello there"));
    }

    @Test
    void listsApprovalsFromConsoleCommand() throws Exception {
        ConversationStore store = new InMemoryConversationStore();
        CodexConsoleRunner runner = new CodexConsoleRunner(new StubTurnExecutor(), store, new StubApprovalService());

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
        ConversationStore store = new InMemoryConversationStore();
        CodexConsoleRunner runner = new CodexConsoleRunner(new StubTurnExecutor(), store, new StubApprovalService());

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

    private static final class StubTurnExecutor implements TurnExecutor {

        @Override
        public CodexTurnResult executeTurn(ThreadId threadId, String input) {
            return new CodexTurnResult(threadId, new TurnId("turn-1"), TurnStatus.COMPLETED, List.of(), "handled: " + input);
        }

        @Override
        public CodexTurnResult resumeTurn(ThreadId threadId, TurnId turnId) {
            return new CodexTurnResult(threadId, turnId, TurnStatus.COMPLETED, List.of(), "resumed turn");
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
}
