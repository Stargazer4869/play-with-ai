package org.dean.codex.cli.command.root;

import picocli.CommandLine;

public final class CodexRootCommandLine {

    private CodexRootCommandLine() {
    }

    public static CommandLine create() {
        return new CommandLine(new CodexRootCommand());
    }
}
