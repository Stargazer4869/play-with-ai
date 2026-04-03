package org.dean.codex.cli.command.session;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record ForkSessionCommand(@Nullable String sessionId,
                                 boolean last,
                                 boolean all,
                                 boolean includeNonInteractive,
                                 @Nullable String prompt) implements SessionCommand {

    public static final String COMMAND_NAME = "fork";

    public ForkSessionCommand {
        if (last && all) {
            throw new IllegalArgumentException("fork cannot use both --last and --all");
        }
    }

    public static ForkSessionCommand parse(List<String> arguments) {
        ResumeSessionCommand.SelectionArguments parsed = ResumeSessionCommand.selectionArguments(arguments);
        return new ForkSessionCommand(parsed.sessionId(), parsed.last(), parsed.all(),
                parsed.includeNonInteractive(), parsed.prompt());
    }

    @Override
    public String commandName() {
        return COMMAND_NAME;
    }

    @Override
    public String summary() {
        return "Fork a previous interactive session.";
    }

    @Override
    public String usage() {
        return "fork [--last|--all] [--include-non-interactive] [--session-id ID] [PROMPT...]";
    }
}
