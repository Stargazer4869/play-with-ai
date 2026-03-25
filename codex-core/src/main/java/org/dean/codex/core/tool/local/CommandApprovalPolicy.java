package org.dean.codex.core.tool.local;

import org.dean.codex.protocol.tool.CommandApproval;

public interface CommandApprovalPolicy {

    CommandApproval evaluate(String command);
}
