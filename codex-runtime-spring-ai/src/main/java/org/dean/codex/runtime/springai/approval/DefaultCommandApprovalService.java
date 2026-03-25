package org.dean.codex.runtime.springai.approval;

import org.dean.codex.core.approval.CommandApprovalService;
import org.dean.codex.core.approval.CommandApprovalStore;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.core.tool.local.ShellCommandTool;
import org.dean.codex.protocol.approval.ApprovalId;
import org.dean.codex.protocol.approval.ApprovalStatus;
import org.dean.codex.protocol.approval.CommandApprovalRequest;
import org.dean.codex.protocol.conversation.ItemId;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.event.TurnEvent;
import org.dean.codex.protocol.tool.ShellCommandResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class DefaultCommandApprovalService implements CommandApprovalService {

    private final CommandApprovalStore commandApprovalStore;
    private final ConversationStore conversationStore;
    private final ShellCommandTool shellCommandTool;

    public DefaultCommandApprovalService(CommandApprovalStore commandApprovalStore,
                                         ConversationStore conversationStore,
                                         ShellCommandTool shellCommandTool) {
        this.commandApprovalStore = commandApprovalStore;
        this.conversationStore = conversationStore;
        this.shellCommandTool = shellCommandTool;
    }

    @Override
    public synchronized CommandApprovalRequest requestApproval(ThreadId threadId,
                                                              TurnId turnId,
                                                              String command,
                                                              String workingDirectory,
                                                              String reason) {
        return commandApprovalStore.list(threadId).stream()
                .filter(request -> request.turnId().equals(turnId))
                .filter(request -> request.status() == ApprovalStatus.PENDING)
                .filter(request -> request.command().equals(command))
                .findFirst()
                .orElseGet(() -> createPendingRequest(threadId, turnId, command, workingDirectory, reason));
    }

    @Override
    public synchronized List<CommandApprovalRequest> approvals(ThreadId threadId) {
        return commandApprovalStore.list(threadId);
    }

    @Override
    public synchronized CommandApprovalRequest approve(ThreadId threadId, String approvalIdPrefix) {
        CommandApprovalRequest request = resolvePending(threadId, approvalIdPrefix);
        Instant now = Instant.now();
        ShellCommandResult result = shellCommandTool.runApprovedCommand(request.command());
        CommandApprovalRequest approvedRequest = new CommandApprovalRequest(
                request.approvalId(),
                request.threadId(),
                request.turnId(),
                request.command(),
                request.workingDirectory(),
                request.reason(),
                ApprovalStatus.APPROVED,
                request.createdAt(),
                now,
                "Approved from CLI.",
                result);
        commandApprovalStore.save(approvedRequest);
        conversationStore.appendTurnEvents(threadId, request.turnId(), List.of(
                turnEvent("approval.approved", "Approved command " + shortApprovalId(request.approvalId()) + ": " + request.command(), now),
                turnEvent("approval.result", summarizeApprovedResult(result), now)
        ));
        return approvedRequest;
    }

    @Override
    public synchronized CommandApprovalRequest reject(ThreadId threadId, String approvalIdPrefix, String reason) {
        CommandApprovalRequest request = resolvePending(threadId, approvalIdPrefix);
        Instant now = Instant.now();
        String note = reason == null || reason.isBlank() ? "Rejected from CLI." : reason.trim();
        CommandApprovalRequest rejectedRequest = new CommandApprovalRequest(
                request.approvalId(),
                request.threadId(),
                request.turnId(),
                request.command(),
                request.workingDirectory(),
                request.reason(),
                ApprovalStatus.REJECTED,
                request.createdAt(),
                now,
                note,
                null);
        commandApprovalStore.save(rejectedRequest);
        conversationStore.appendTurnEvents(threadId, request.turnId(), List.of(
                turnEvent("approval.rejected", "Rejected command " + shortApprovalId(request.approvalId()) + ": " + note, now)
        ));
        return rejectedRequest;
    }

    private CommandApprovalRequest createPendingRequest(ThreadId threadId,
                                                        TurnId turnId,
                                                        String command,
                                                        String workingDirectory,
                                                        String reason) {
        Instant now = Instant.now();
        CommandApprovalRequest request = new CommandApprovalRequest(
                new ApprovalId(UUID.randomUUID().toString()),
                threadId,
                turnId,
                command,
                workingDirectory == null ? "" : workingDirectory,
                reason == null ? "" : reason,
                ApprovalStatus.PENDING,
                now,
                now,
                "",
                null);
        commandApprovalStore.save(request);
        return request;
    }

    private CommandApprovalRequest resolvePending(ThreadId threadId, String approvalIdPrefix) {
        if (approvalIdPrefix == null || approvalIdPrefix.isBlank()) {
            throw new IllegalArgumentException("Approval id prefix must not be blank.");
        }

        List<CommandApprovalRequest> matches = commandApprovalStore.list(threadId).stream()
                .filter(request -> request.status() == ApprovalStatus.PENDING)
                .filter(request -> request.approvalId().value().startsWith(approvalIdPrefix))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No pending approval matched: " + approvalIdPrefix);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Approval id prefix is ambiguous: " + approvalIdPrefix);
        }
        return matches.get(0);
    }

    private TurnEvent turnEvent(String type, String detail, Instant createdAt) {
        return new TurnEvent(new ItemId(UUID.randomUUID().toString()), type, detail, createdAt);
    }

    private String summarizeApprovedResult(ShellCommandResult result) {
        if (result == null) {
            return "Approved command completed without a recorded result.";
        }
        StringBuilder summary = new StringBuilder("Approved command executed: success=")
                .append(result.success());
        if (result.executed()) {
            summary.append(" exitCode=").append(result.exitCode());
        }
        if (result.timedOut()) {
            summary.append(" timedOut=true");
        }
        if (result.error() != null && !result.error().isBlank()) {
            summary.append(" error=").append(result.error());
        }
        return summary.toString();
    }

    private String shortApprovalId(ApprovalId approvalId) {
        String value = approvalId.value();
        return value.length() <= 8 ? value : value.substring(0, 8);
    }
}
