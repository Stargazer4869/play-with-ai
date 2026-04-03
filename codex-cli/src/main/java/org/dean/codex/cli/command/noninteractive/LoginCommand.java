package org.dean.codex.cli.command.noninteractive;

import picocli.CommandLine.Command;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Command(
        name = "login",
        description = "Authenticate with Codex.",
        mixinStandardHelpOptions = true,
        sortOptions = false
)
public record LoginCommand(List<String> arguments) implements NonInteractiveCommand {

    public static final String COMMAND_NAME = "login";

    public LoginCommand {
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
    }

    public static LoginCommand parse(List<String> arguments) {
        return new LoginCommand(new ArrayList<>(Objects.requireNonNull(arguments, "arguments")));
    }

    @Override
    public String commandName() {
        return COMMAND_NAME;
    }

    @Override
    public String summary() {
        return "Authenticate with Codex.";
    }

    @Override
    public String usage() {
        return "login [ARGS...]";
    }
}
