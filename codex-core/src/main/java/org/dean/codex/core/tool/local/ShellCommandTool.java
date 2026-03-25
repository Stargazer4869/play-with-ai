package org.dean.codex.core.tool.local;

import org.dean.codex.protocol.tool.ShellCommandResult;

public interface ShellCommandTool {

    ShellCommandResult runCommand(String command);

    ShellCommandResult runApprovedCommand(String command);
}
