package org.dean.codex.cli.command.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionCommandRegistryTest {

    @Test
    void routesCanonicalSessionCommands() {
        assertTrue(SessionCommandRegistry.parse("resume", List.of("--last")) instanceof ResumeSessionCommand);
        assertTrue(SessionCommandRegistry.parse("fork", List.of("--all")) instanceof ForkSessionCommand);
        assertTrue(SessionCommandRegistry.parse("completion", List.of("zsh")) instanceof CompletionSessionCommand);
    }

    @Test
    void exposesCanonicalCommandOrder() {
        assertEquals(List.of("resume", "fork", "completion"), SessionCommandRegistry.commandNames());
    }

    @Test
    void rejectsUnknownCommands() {
        assertThrows(IllegalArgumentException.class, () -> SessionCommandRegistry.parse("status", List.of()));
    }
}
