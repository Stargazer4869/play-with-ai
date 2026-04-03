package org.dean.codex.cli.interactive;

import java.util.Objects;

public record SlashCommandInvocation(
        SlashCommandSpec command,
        SlashCommandPrefix prefix,
        String arguments,
        String rawInput) {

    public SlashCommandInvocation {
        command = Objects.requireNonNull(command, "command");
        prefix = Objects.requireNonNull(prefix, "prefix");
        arguments = arguments == null ? "" : arguments;
        rawInput = Objects.requireNonNull(rawInput, "rawInput");
    }
}
