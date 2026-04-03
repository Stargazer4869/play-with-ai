package org.dean.codex.cli.command.session;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public record ResumeSessionCommand(@Nullable String sessionId,
                                   boolean last,
                                   boolean all,
                                   boolean includeNonInteractive,
                                   @Nullable String prompt) implements SessionCommand {

    public static final String COMMAND_NAME = "resume";

    public ResumeSessionCommand {
        if (last && all) {
            throw new IllegalArgumentException("resume cannot use both --last and --all");
        }
    }

    public static ResumeSessionCommand parse(List<String> arguments) {
        SelectionArguments parsed = SelectionArguments.parse(arguments);
        return new ResumeSessionCommand(parsed.sessionId(), parsed.last(), parsed.all(),
                parsed.includeNonInteractive(), parsed.prompt());
    }

    @Override
    public String commandName() {
        return COMMAND_NAME;
    }

    @Override
    public String summary() {
        return "Resume a previous interactive session.";
    }

    @Override
    public String usage() {
        return "resume [--last|--all] [--include-non-interactive] [--session-id ID] [PROMPT...]";
    }

    static SelectionArguments selectionArguments(List<String> arguments) {
        return SelectionArguments.parse(arguments);
    }

    record SelectionArguments(@Nullable String sessionId,
                              boolean last,
                              boolean all,
                              boolean includeNonInteractive,
                              @Nullable String prompt) {

        static SelectionArguments parse(List<String> arguments) {
            boolean last = false;
            boolean all = false;
            boolean includeNonInteractive = false;
            String sessionId = null;
            String explicitPrompt = null;
            List<String> positional = new ArrayList<>();

            for (int index = 0; index < arguments.size(); index++) {
                String argument = arguments.get(index);
                switch (argument) {
                    case "--last" -> last = true;
                    case "--all" -> all = true;
                    case "--include-non-interactive" -> includeNonInteractive = true;
                    case "--session-id", "--thread-id", "-s" -> {
                        index = requireValue(arguments, index, argument);
                        sessionId = arguments.get(index);
                    }
                    case "--prompt", "-p" -> {
                        index = requireValue(arguments, index, argument);
                        explicitPrompt = joinTail(arguments, index);
                        index = arguments.size();
                    }
                    default -> {
                        if (argument.startsWith("-")) {
                            throw new IllegalArgumentException("Unknown resume option: " + argument);
                        }
                        positional.add(argument);
                    }
                }
            }

            if (last && all) {
                throw new IllegalArgumentException("resume cannot use both --last and --all");
            }
            if (!positional.isEmpty() && sessionId == null) {
                sessionId = positional.remove(0);
            }
            if ((last || all) && sessionId != null) {
                throw new IllegalArgumentException("resume cannot combine a selected session id with --last or --all");
            }
            if (sessionId != null && !positional.isEmpty() && explicitPrompt != null) {
                throw new IllegalArgumentException("resume prompt may be provided once");
            }
            String prompt = explicitPrompt != null ? explicitPrompt : joinTail(positional);
            return new SelectionArguments(sessionId, last, all, includeNonInteractive, emptyToNull(prompt));
        }
    }

    static int requireValue(List<String> arguments, int index, String optionName) {
        if (index + 1 >= arguments.size()) {
            throw new IllegalArgumentException(optionName + " requires a value");
        }
        return index + 1;
    }

    static String joinTail(List<String> values) {
        return values.isEmpty() ? "" : String.join(" ", values);
    }

    static String joinTail(List<String> values, int startIndex) {
        if (startIndex >= values.size()) {
            return "";
        }
        return String.join(" ", values.subList(startIndex, values.size()));
    }

    static @Nullable String emptyToNull(String value) {
        return value.isBlank() ? null : value;
    }
}
