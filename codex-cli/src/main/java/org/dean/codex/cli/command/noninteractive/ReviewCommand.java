package org.dean.codex.cli.command.noninteractive;

import picocli.CommandLine.Command;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Command(
        name = "review",
        description = "Start a review session.",
        mixinStandardHelpOptions = true,
        sortOptions = false
)
public record ReviewCommand(List<String> arguments) implements NonInteractiveCommand {

    public static final String COMMAND_NAME = "review";

    public ReviewCommand {
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
    }

    public static ReviewCommand parse(List<String> arguments) {
        return new ReviewCommand(new ArrayList<>(Objects.requireNonNull(arguments, "arguments")));
    }

    @Override
    public String commandName() {
        return COMMAND_NAME;
    }

    @Override
    public String summary() {
        return "Start a review session.";
    }

    @Override
    public String usage() {
        return "review [ARGS...]";
    }
}
