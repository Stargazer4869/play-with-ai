package org.dean.codex.cli.command.session;

/**
 * Common metadata for top-level session-oriented CLI commands.
 */
public interface SessionCommand {

    String commandName();

    String summary();

    String usage();

    default String help() {
        return "/" + usage() + System.lineSeparator() + summary();
    }
}
