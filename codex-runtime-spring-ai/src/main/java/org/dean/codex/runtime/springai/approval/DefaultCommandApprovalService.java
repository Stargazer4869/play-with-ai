package org.dean.codex.runtime.springai.approval;

import org.dean.codex.core.approval.CommandApprovalService;
import org.dean.codex.core.approval.CommandApprovalStore;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.core.history.ThreadHistoryStore;
import org.dean.codex.core.tool.local.ShellCommandTool;
import org.dean.codex.protocol.approval.ApprovalId;
import org.dean.codex.protocol.approval.ApprovalStatus;
import org.dean.codex.protocol.approval.CommandApprovalRequest;
import org.dean.codex.protocol.conversation.ItemId;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.item.ApprovalItem;
import org.dean.codex.protocol.item.ApprovalState;
import org.dean.codex.protocol.item.TurnItem;
import org.dean.codex.runtime.springai.history.ThreadHistoryMapper;
import org.dean.codex.protocol.tool.ShellCommandResult;

import java.time.Instant;
import java.util.List;

public class DefaultCommandApprovalService implements CommandApprovalService {

    private final CommandApprovalStore commandApprovalStore;
    private final ConversationStore conversationStore;
    private final ThreadHistoryStore threadHistoryStore;
    private final ShellCommandTool shellCommandTool;

    public DefaultCommandApprovalService(CommandApprovalStore commandApprovalStore,
                                         ConversationStore conversationStore,
                                         ThreadHistoryStore threadHistoryStore,
                                         ShellCommandTool shellCommandTool) {
        this.commandApprovalStore = commandApprovalStore;
        this.conversationStore = conversationStore;
        this.threadHistoryStore = threadHistoryStore;
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
        List<TurnItem> approvalItems = List.of(
                approvalItem(ApprovalState.APPROVED, request.approvalId(), request.command(),
                        "Approved command " + shortApprovalId(request.approvalId()) + ": " + request.command(), now),
                approvalItem(ApprovalState.RESULT, request.approvalId(), request.command(), summarizeApprovedResult(result), now)
        );
        conversationStore.appendTurnItems(threadId, request.turnId(), approvalItems);
        threadHistoryStore.append(threadId, ThreadHistoryMapper.map(request.turnId(), approvalItems));
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
        List<TurnItem> approvalItems = List.of(
                approvalItem(ApprovalState.REJECTED, request.approvalId(), request.command(),
                        "Rejected command " + shortApprovalId(request.approvalId()) + ": " + note, now)
        );
        conversationStore.appendTurnItems(threadId, request.turnId(), approvalItems);
        threadHistoryStore.append(threadId, ThreadHistoryMapper.map(request.turnId(), approvalItems));
        return rejectedRequest;
    }

    private CommandApprovalRequest createPendingRequest(ThreadId threadId,
                                                        TurnId turnId,
                                                        String command,
                                                        String workingDirectory,
                                                        String reason) {
        Instant now = Instant.now();
        CommandApprovalRequest request = new CommandApprovalRequest(
                new ApprovalId(java.util.UUID.randomUUID().toString()),
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

    private ApprovalItem approvalItem(ApprovalState state,
                                      ApprovalId approvalId,
                                      String command,
                                      String detail,
                                      Instant createdAt) {
        return new ApprovalItem(
                new ItemId(java.util.UUID.randomUUID().toString()),
                state,
                approvalId == null ? "" : approvalId.value(),
                command == null ? "" : command,
                detail,
                createdAt);
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
