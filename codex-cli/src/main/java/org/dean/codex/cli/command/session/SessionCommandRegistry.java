package org.dean.codex.cli.command.session;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small routing helper that can be wired into a root parser later.
 */
public final class SessionCommandRegistry {

    private static final Map<String, SessionCommandFactory> COMMANDS = new LinkedHashMap<>();

    static {
        COMMANDS.put(ResumeSessionCommand.COMMAND_NAME, ResumeSessionCommand::parse);
        COMMANDS.put(ForkSessionCommand.COMMAND_NAME, ForkSessionCommand::parse);
        COMMANDS.put(CompletionSessionCommand.COMMAND_NAME, CompletionSessionCommand::parse);
    }

    private SessionCommandRegistry() {
    }

    public static SessionCommand parse(String commandName, List<String> arguments) {
        SessionCommandFactory factory = COMMANDS.get(normalize(commandName));
        if (factory == null) {
            throw new IllegalArgumentException("Unknown session command: " + commandName);
        }
        return factory.parse(arguments);
    }

    public static List<String> commandNames() {
        return List.copyOf(COMMANDS.keySet());
    }

    private static String normalize(String commandName) {
        return commandName.toLowerCase(Locale.ROOT);
    }

    @FunctionalInterface
    private interface SessionCommandFactory {
        SessionCommand parse(List<String> arguments);
    }
}
