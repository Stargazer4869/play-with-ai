package org.dean.codex.cli.command.noninteractive;

import picocli.CommandLine.Command;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Command(
        name = "sandbox",
        description = "Inspect or configure sandbox settings.",
        mixinStandardHelpOptions = true,
        sortOptions = false
)
public record SandboxCommand(List<String> arguments) implements NonInteractiveCommand {

    public static final String COMMAND_NAME = "sandbox";

    public SandboxCommand {
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
    }

    public static SandboxCommand parse(List<String> arguments) {
        return new SandboxCommand(new ArrayList<>(Objects.requireNonNull(arguments, "arguments")));
    }

    @Override
    public String commandName() {
        return COMMAND_NAME;
    }

    @Override
    public String summary() {
        return "Inspect or configure sandbox settings.";
    }

    @Override
    public String usage() {
        return "sandbox [ARGS...]";
    }
}
