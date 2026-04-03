package org.dean.codex.cli.command.noninteractive;

import picocli.CommandLine.Command;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Command(
        name = "exec",
        description = "Execute a prompt non-interactively.",
        mixinStandardHelpOptions = true,
        sortOptions = false
)
public record ExecCommand(List<String> promptTokens) implements NonInteractiveCommand {

    public static final String COMMAND_NAME = "exec";

    public ExecCommand {
        promptTokens = List.copyOf(Objects.requireNonNull(promptTokens, "promptTokens"));
    }

    public static ExecCommand parse(List<String> arguments) {
        return new ExecCommand(new ArrayList<>(Objects.requireNonNull(arguments, "arguments")));
    }

    @Override
    public String commandName() {
        return COMMAND_NAME;
    }

    @Override
    public String summary() {
        return "Execute a prompt non-interactively.";
    }

    @Override
    public String usage() {
        return "exec [PROMPT...]";
    }

    @Override
    public List<String> arguments() {
        return promptTokens;
    }
}
