package org.dean.codex.cli.command.session;

import java.util.List;
import java.util.Locale;

public record CompletionSessionCommand(CompletionSessionCommand.Shell shell) implements SessionCommand {

    public static final String COMMAND_NAME = "completion";

    public CompletionSessionCommand {
        if (shell == null) {
            throw new IllegalArgumentException("shell is required");
        }
    }

    public static CompletionSessionCommand parse(List<String> arguments) {
        Shell shell = Shell.BASH;
        boolean shellSpecified = false;
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            switch (argument) {
                case "--shell", "-s" -> {
                    index = requireValue(arguments, index, argument);
                    if (shellSpecified) {
                        throw new IllegalArgumentException("completion accepts only one shell value");
                    }
                    shell = Shell.from(arguments.get(index));
                    shellSpecified = true;
                }
                default -> {
                    if (argument.startsWith("-")) {
                        throw new IllegalArgumentException("Unknown completion option: " + argument);
                    }
                    if (shellSpecified) {
                        throw new IllegalArgumentException("completion accepts only one shell value");
                    }
                    shell = Shell.from(argument);
                    shellSpecified = true;
                }
            }
        }
        return new CompletionSessionCommand(shell);
    }

    @Override
    public String commandName() {
        return COMMAND_NAME;
    }

    @Override
    public String summary() {
        return "Generate shell completion scripts.";
    }

    @Override
    public String usage() {
        return "completion [--shell SHELL]";
    }

    static int requireValue(List<String> arguments, int index, String optionName) {
        if (index + 1 >= arguments.size()) {
            throw new IllegalArgumentException(optionName + " requires a value");
        }
        return index + 1;
    }

    public enum Shell {
        BASH,
        ZSH,
        FISH,
        POWERSHELL;

        static Shell from(String value) {
            String normalized = value.toUpperCase(Locale.ROOT);
            for (Shell shell : values()) {
                if (shell.name().equals(normalized)) {
                    return shell;
                }
            }
            throw new IllegalArgumentException("Unknown shell: " + value);
        }
    }
}
