package org.dean.codex.cli.command.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeSessionCommandTest {

    @Test
    void parsesExplicitSessionIdAndPrompt() {
        ResumeSessionCommand command = ResumeSessionCommand.parse(List.of(
                "--session-id", "1234",
                "continue", "work"
        ));

        assertEquals("1234", command.sessionId());
        assertTrue(command.commandName().equals("resume"));
        assertEquals(false, command.last());
        assertEquals(false, command.all());
        assertEquals(false, command.includeNonInteractive());
        assertEquals("continue work", command.prompt());
        assertTrue(command.help().startsWith("/resume"));
    }

    @Test
    void parsesLastAndIncludeNonInteractiveFlags() {
        ResumeSessionCommand command = ResumeSessionCommand.parse(List.of(
                "--last",
                "--include-non-interactive"
        ));

        assertEquals(null, command.sessionId());
        assertTrue(command.last());
        assertEquals(false, command.all());
        assertTrue(command.includeNonInteractive());
        assertEquals(null, command.prompt());
        assertTrue(command.usage().contains("[--last|--all]"));
    }

    @Test
    void rejectsConflictingSelectionFlags() {
        assertThrows(IllegalArgumentException.class, () ->
                ResumeSessionCommand.parse(List.of("--last", "--all")));
    }
}
