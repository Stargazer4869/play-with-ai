package org.dean.codex.cli.interactive;

import java.util.Objects;
import java.util.Optional;

public final class SlashCommandParseResult {
    public enum Kind {
        COMMAND,
        UNKNOWN_COMMAND,
        NON_COMMAND,
        EMPTY
    }

    private final Kind kind;
    private final SlashCommandInvocation invocation;
    private final String rawInput;
    private final String commandToken;
    private final String arguments;

    private SlashCommandParseResult(
            Kind kind,
            SlashCommandInvocation invocation,
            String rawInput,
            String commandToken,
            String arguments) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.invocation = invocation;
        this.rawInput = rawInput;
        this.commandToken = commandToken;
        this.arguments = arguments == null ? "" : arguments;
    }

    public static SlashCommandParseResult command(SlashCommandInvocation invocation) {
        return new SlashCommandParseResult(Kind.COMMAND, Objects.requireNonNull(invocation, "invocation"), invocation.rawInput(), invocation.command().name(), invocation.arguments());
    }

    public static SlashCommandParseResult unknown(String rawInput, String commandToken, String arguments) {
        return new SlashCommandParseResult(Kind.UNKNOWN_COMMAND, null, rawInput, commandToken, arguments);
    }

    public static SlashCommandParseResult nonCommand(String rawInput) {
        return new SlashCommandParseResult(Kind.NON_COMMAND, null, rawInput, null, "");
    }

    public static SlashCommandParseResult empty(String rawInput) {
        return new SlashCommandParseResult(Kind.EMPTY, null, rawInput, null, "");
    }

    public Kind kind() {
        return kind;
    }

    public Optional<SlashCommandInvocation> invocation() {
        return Optional.ofNullable(invocation);
    }

    public String rawInput() {
        return rawInput;
    }

    public Optional<String> commandToken() {
        return Optional.ofNullable(commandToken);
    }

    public String arguments() {
        return arguments;
    }

    public boolean isCommand() {
        return kind == Kind.COMMAND;
    }

    public boolean isUnknownCommand() {
        return kind == Kind.UNKNOWN_COMMAND;
    }

    public boolean isNonCommand() {
        return kind == Kind.NON_COMMAND;
    }

    public boolean isEmpty() {
        return kind == Kind.EMPTY;
    }
}
