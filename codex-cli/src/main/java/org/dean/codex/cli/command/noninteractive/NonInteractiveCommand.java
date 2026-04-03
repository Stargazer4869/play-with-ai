package org.dean.codex.cli.command.noninteractive;

import java.util.List;

/**
 * Common metadata for top-level non-interactive CLI commands.
 */
public interface NonInteractiveCommand extends Runnable {

    String commandName();

    String summary();

    String usage();

    List<String> arguments();

    default String help() {
        return "Usage: codex " + usage() + System.lineSeparator() + summary();
    }

    @Override
    default void run() {
        execute();
    }

    default void execute() {
        throw new CommandNotYetWiredException(commandName());
    }
}
