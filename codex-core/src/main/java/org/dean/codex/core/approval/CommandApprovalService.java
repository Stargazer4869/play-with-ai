package org.dean.codex.core.approval;

import org.dean.codex.protocol.approval.CommandApprovalRequest;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;

import java.util.List;

public interface CommandApprovalService {

    CommandApprovalRequest requestApproval(ThreadId threadId,
                                          TurnId turnId,
                                          String command,
                                          String workingDirectory,
                                          String reason);

    List<CommandApprovalRequest> approvals(ThreadId threadId);

    CommandApprovalRequest approve(ThreadId threadId, String approvalIdPrefix);

    CommandApprovalRequest reject(ThreadId threadId, String approvalIdPrefix, String reason);
}
