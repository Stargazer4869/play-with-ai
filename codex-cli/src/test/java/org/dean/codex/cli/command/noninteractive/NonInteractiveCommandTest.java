package org.dean.codex.cli.command.noninteractive;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NonInteractiveCommandTest {

    @Test
    void execCommandPreservesPromptTokensAndFailsClearly() {
        ExecCommand command = ExecCommand.parse(List.of("plan", "the", "next", "step"));

        assertEquals("exec", command.commandName());
        assertEquals(List.of("plan", "the", "next", "step"), command.arguments());
        assertTrue(command.help().startsWith("Usage: codex exec"));

        CommandNotYetWiredException exception = assertThrows(CommandNotYetWiredException.class, command::execute);
        assertTrue(exception.getMessage().contains("exec"));
        assertTrue(exception.getMessage().contains("not wired to runtime yet"));
    }

    @Test
    void reviewCommandPreservesArgumentsAndFailsClearly() {
        ReviewCommand command = ReviewCommand.parse(List.of("--dry-run", "src/main/java"));

        assertEquals("review", command.commandName());
        assertEquals(List.of("--dry-run", "src/main/java"), command.arguments());
        assertTrue(command.help().startsWith("Usage: codex review"));

        CommandNotYetWiredException exception = assertThrows(CommandNotYetWiredException.class, command::execute);
        assertTrue(exception.getMessage().contains("review"));
    }

    @Test
    void sandboxCommandPreservesArgumentsAndFailsClearly() {
        SandboxCommand command = SandboxCommand.parse(List.of("status"));

        assertEquals("sandbox", command.commandName());
        assertEquals(List.of("status"), command.arguments());
        assertTrue(command.help().startsWith("Usage: codex sandbox"));

        CommandNotYetWiredException exception = assertThrows(CommandNotYetWiredException.class, command::execute);
        assertTrue(exception.getMessage().contains("sandbox"));
    }

    @Test
    void authCommandsExposeMetadataAndFailClearly() {
        LoginCommand loginCommand = LoginCommand.parse(List.of("--provider", "openai"));
        LogoutCommand logoutCommand = LogoutCommand.parse(List.of("--revoke"));

        assertEquals("login", loginCommand.commandName());
        assertEquals(List.of("--provider", "openai"), loginCommand.arguments());
        assertTrue(loginCommand.help().startsWith("Usage: codex login"));

        assertEquals("logout", logoutCommand.commandName());
        assertEquals(List.of("--revoke"), logoutCommand.arguments());
        assertTrue(logoutCommand.help().startsWith("Usage: codex logout"));

        assertTrue(assertThrows(CommandNotYetWiredException.class, loginCommand::execute).getMessage().contains("login"));
        assertTrue(assertThrows(CommandNotYetWiredException.class, logoutCommand::execute).getMessage().contains("logout"));
    }
}
