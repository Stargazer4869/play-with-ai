package org.dean.codex.cli.interactive;

import java.util.Objects;

public final class SlashCommandParser {
    private final SlashCommandRegistry registry;

    public SlashCommandParser(SlashCommandRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public SlashCommandParseResult parse(String input) {
        if (input == null) {
            return SlashCommandParseResult.empty(null);
        }
        if (input.isBlank()) {
            return SlashCommandParseResult.empty(input);
        }
        String trimmed = input.stripLeading();
        if (trimmed.isEmpty()) {
            return SlashCommandParseResult.empty(input);
        }
        char prefix = trimmed.charAt(0);
        if (prefix != SlashCommandPrefix.SLASH.prefixChar()) {
            return SlashCommandParseResult.nonCommand(input);
        }

        int cursor = 1;
        while (cursor < trimmed.length() && !Character.isWhitespace(trimmed.charAt(cursor))) {
            cursor++;
        }
        String token = trimmed.substring(1, cursor);
        if (token.isEmpty()) {
            return SlashCommandParseResult.unknown(input, "", trimmed.substring(cursor).stripLeading());
        }

        String arguments = cursor < trimmed.length() ? trimmed.substring(cursor).stripLeading() : "";
        return registry.find(token)
                .map(command -> SlashCommandParseResult.command(
                        new SlashCommandInvocation(
                                command,
                                SlashCommandPrefix.SLASH,
                                arguments,
                                input)))
                .orElseGet(() -> SlashCommandParseResult.unknown(input, token, arguments));
    }
}
