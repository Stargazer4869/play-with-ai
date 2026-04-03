package org.dean.codex.cli.command.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForkSessionCommandTest {

    @Test
    void parsesPositionalSessionIdAndPrompt() {
        ForkSessionCommand command = ForkSessionCommand.parse(List.of(
                "abcd1234",
                "refine", "prompt"
        ));

        assertEquals("abcd1234", command.sessionId());
        assertEquals(false, command.last());
        assertEquals(false, command.all());
        assertEquals(false, command.includeNonInteractive());
        assertEquals("refine prompt", command.prompt());
        assertTrue(command.help().startsWith("/fork"));
    }

    @Test
    void parsesAllAndIncludeNonInteractiveFlags() {
        ForkSessionCommand command = ForkSessionCommand.parse(List.of(
                "--all",
                "--include-non-interactive"
        ));

        assertEquals(null, command.sessionId());
        assertEquals(false, command.last());
        assertTrue(command.all());
        assertTrue(command.includeNonInteractive());
        assertEquals(null, command.prompt());
        assertTrue(command.usage().contains("[--last|--all]"));
    }

    @Test
    void rejectsConflictingSelectionFlags() {
        assertThrows(IllegalArgumentException.class, () ->
                ForkSessionCommand.parse(List.of("--last", "--all")));
    }
}
