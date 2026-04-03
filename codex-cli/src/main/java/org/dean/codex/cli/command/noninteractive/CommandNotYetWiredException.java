package org.dean.codex.cli.command.noninteractive;

/**
 * Signals that a command exists as a top-level CLI model but has not been wired
 * to runtime execution yet.
 */
public final class CommandNotYetWiredException extends UnsupportedOperationException {

    public CommandNotYetWiredException(String commandName) {
        super("Top-level non-interactive command '" + commandName + "' is defined but not wired to runtime yet.");
    }
}
