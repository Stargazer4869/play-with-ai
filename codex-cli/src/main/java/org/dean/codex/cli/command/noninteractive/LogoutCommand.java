package org.dean.codex.cli.command.noninteractive;

import picocli.CommandLine.Command;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Command(
        name = "logout",
        description = "Clear stored authentication.",
        mixinStandardHelpOptions = true,
        sortOptions = false
)
public record LogoutCommand(List<String> arguments) implements NonInteractiveCommand {

    public static final String COMMAND_NAME = "logout";

    public LogoutCommand {
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
    }

    public static LogoutCommand parse(List<String> arguments) {
        return new LogoutCommand(new ArrayList<>(Objects.requireNonNull(arguments, "arguments")));
    }

    @Override
    public String commandName() {
        return COMMAND_NAME;
    }

    @Override
    public String summary() {
        return "Clear stored authentication.";
    }

    @Override
    public String usage() {
        return "logout [ARGS...]";
    }
}
