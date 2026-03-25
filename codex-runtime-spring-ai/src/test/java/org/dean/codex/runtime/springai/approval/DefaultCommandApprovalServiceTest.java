package org.dean.codex.runtime.springai.approval;

import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.core.conversation.InMemoryConversationStore;
import org.dean.codex.core.tool.local.ShellCommandTool;
import org.dean.codex.protocol.approval.ApprovalStatus;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.tool.CommandApprovalDecision;
import org.dean.codex.protocol.tool.ShellCommandResult;
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
    void approveExecutesCommandAndAppendsTurnEvents() {
        ConversationStore conversationStore = new InMemoryConversationStore();
        ThreadId threadId = conversationStore.createThread("Approval thread");
        TurnId turnId = conversationStore.startTurn(threadId, "run tests", Instant.now());
        FileSystemCommandApprovalStore approvalStore = new FileSystemCommandApprovalStore(storageRoot);
        DefaultCommandApprovalService service = new DefaultCommandApprovalService(
                approvalStore,
                conversationStore,
                new StubShellCommandTool());

        var request = service.requestApproval(threadId, turnId, "mvn test", "/tmp/workspace", "Needs approval");
        var approved = service.approve(threadId, request.approvalId().value().substring(0, 8));

        assertEquals(ApprovalStatus.APPROVED, approved.status());
        assertTrue(approved.executionResult().executed());
        assertTrue(conversationStore.turns(threadId).get(0).events().stream()
                .anyMatch(event -> event.type().equals("approval.approved")));
        assertTrue(conversationStore.turns(threadId).get(0).events().stream()
                .anyMatch(event -> event.type().equals("approval.result")));
    }

    @Test
    void rejectMarksApprovalRejected() {
        ConversationStore conversationStore = new InMemoryConversationStore();
        ThreadId threadId = conversationStore.createThread("Approval thread");
        TurnId turnId = conversationStore.startTurn(threadId, "run tests", Instant.now());
        FileSystemCommandApprovalStore approvalStore = new FileSystemCommandApprovalStore(storageRoot);
        DefaultCommandApprovalService service = new DefaultCommandApprovalService(
                approvalStore,
                conversationStore,
                new StubShellCommandTool());

        var request = service.requestApproval(threadId, turnId, "mvn test", "/tmp/workspace", "Needs approval");
        var rejected = service.reject(threadId, request.approvalId().value().substring(0, 8), "Not now");

        assertEquals(ApprovalStatus.REJECTED, rejected.status());
        assertTrue(conversationStore.turns(threadId).get(0).events().stream()
                .anyMatch(event -> event.type().equals("approval.rejected")));
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
