package org.dean.codex.runtime.springai.approval;

import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.core.conversation.InMemoryConversationStore;
import org.dean.codex.core.tool.local.ShellCommandTool;
import org.dean.codex.protocol.approval.ApprovalStatus;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.history.HistoryApprovalItem;
import org.dean.codex.protocol.item.ApprovalItem;
import org.dean.codex.protocol.item.ApprovalState;
import org.dean.codex.protocol.tool.CommandApprovalDecision;
import org.dean.codex.protocol.tool.ShellCommandResult;
import org.dean.codex.runtime.springai.history.InMemoryThreadHistoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultCommandApprovalServiceTest {

    @TempDir
    Path storageRoot;

    @Test
    void approveExecutesCommandAndAppendsTurnItems() {
        ConversationStore conversationStore = new InMemoryConversationStore();
        InMemoryThreadHistoryStore historyStore = new InMemoryThreadHistoryStore();
        ThreadId threadId = conversationStore.createThread("Approval thread");
        TurnId turnId = conversationStore.startTurn(threadId, "run tests", Instant.now());
        FileSystemCommandApprovalStore approvalStore = new FileSystemCommandApprovalStore(storageRoot);
        DefaultCommandApprovalService service = new DefaultCommandApprovalService(
                approvalStore,
                conversationStore,
                historyStore,
                new StubShellCommandTool());

        var request = service.requestApproval(threadId, turnId, "mvn test", "/tmp/workspace", "Needs approval");
        var approved = service.approve(threadId, request.approvalId().value().substring(0, 8));

        assertEquals(ApprovalStatus.APPROVED, approved.status());
        assertTrue(approved.executionResult().executed());
        assertTrue(conversationStore.turns(threadId).get(0).items().stream()
                .filter(ApprovalItem.class::isInstance)
                .map(ApprovalItem.class::cast)
                .anyMatch(item -> item.state() == ApprovalState.APPROVED));
        assertTrue(conversationStore.turns(threadId).get(0).items().stream()
                .filter(ApprovalItem.class::isInstance)
                .map(ApprovalItem.class::cast)
                .anyMatch(item -> item.state() == ApprovalState.RESULT));
        assertEquals(2, historyStore.read(threadId).size());
        assertTrue(historyStore.read(threadId).stream().allMatch(HistoryApprovalItem.class::isInstance));
    }

    @Test
    void rejectMarksApprovalRejected() {
        ConversationStore conversationStore = new InMemoryConversationStore();
        InMemoryThreadHistoryStore historyStore = new InMemoryThreadHistoryStore();
        ThreadId threadId = conversationStore.createThread("Approval thread");
        TurnId turnId = conversationStore.startTurn(threadId, "run tests", Instant.now());
        FileSystemCommandApprovalStore approvalStore = new FileSystemCommandApprovalStore(storageRoot);
        DefaultCommandApprovalService service = new DefaultCommandApprovalService(
                approvalStore,
                conversationStore,
                historyStore,
                new StubShellCommandTool());

        var request = service.requestApproval(threadId, turnId, "mvn test", "/tmp/workspace", "Needs approval");
        var rejected = service.reject(threadId, request.approvalId().value().substring(0, 8), "Not now");

        assertEquals(ApprovalStatus.REJECTED, rejected.status());
        assertTrue(conversationStore.turns(threadId).get(0).items().stream()
                .filter(ApprovalItem.class::isInstance)
                .map(ApprovalItem.class::cast)
                .anyMatch(item -> item.state() == ApprovalState.REJECTED));
        assertEquals(1, historyStore.read(threadId).size());
        assertTrue(historyStore.read(threadId).get(0) instanceof HistoryApprovalItem);
    }

    private static final class StubShellCommandTool implements ShellCommandTool {

        @Override
        public ShellCommandResult runCommand(String command) {
            return new ShellCommandResult(
                    false,
                    command,
                    -1,
                    "",
                    "",
                    false,
                    "/tmp/workspace",
                    false,
                    CommandApprovalDecision.REQUIRE_APPROVAL,
                    "Needs approval",
                    "Command requires approval before execution.");
        }

        @Override
        public ShellCommandResult runApprovedCommand(String command) {
            return new ShellCommandResult(
                    true,
                    command,
                    0,
                    "ok",
                    "",
                    false,
                    "/tmp/workspace",
                    true,
                    CommandApprovalDecision.ALLOW,
                    "Approved",
                    "");
        }
    }
}
