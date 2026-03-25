package org.dean.codex.core.approval;

import org.dean.codex.protocol.approval.ApprovalId;
import org.dean.codex.protocol.approval.CommandApprovalRequest;
import org.dean.codex.protocol.conversation.ThreadId;

import java.util.List;
import java.util.Optional;

public interface CommandApprovalStore {

    void save(CommandApprovalRequest request);

    Optional<CommandApprovalRequest> find(ApprovalId approvalId);

    List<CommandApprovalRequest> list(ThreadId threadId);
}
