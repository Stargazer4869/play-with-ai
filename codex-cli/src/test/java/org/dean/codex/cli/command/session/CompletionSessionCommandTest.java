package org.dean.codex.cli.command.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionSessionCommandTest {

    @Test
    void defaultsToBashCompletion() {
        CompletionSessionCommand command = CompletionSessionCommand.parse(List.of());

        assertEquals(CompletionSessionCommand.Shell.BASH, command.shell());
        assertTrue(command.help().startsWith("/completion"));
        assertTrue(command.usage().contains("[--shell SHELL]"));
    }

    @Test
    void parsesExplicitShellValue() {
        CompletionSessionCommand command = CompletionSessionCommand.parse(List.of("--shell", "zsh"));

        assertEquals(CompletionSessionCommand.Shell.ZSH, command.shell());
    }

    @Test
    void acceptsPositionalShellValue() {
        CompletionSessionCommand command = CompletionSessionCommand.parse(List.of("fish"));

        assertEquals(CompletionSessionCommand.Shell.FISH, command.shell());
    }

    @Test
    void rejectsUnknownShellValue() {
        assertThrows(IllegalArgumentException.class, () ->
                CompletionSessionCommand.parse(List.of("plan9")));
    }
}
