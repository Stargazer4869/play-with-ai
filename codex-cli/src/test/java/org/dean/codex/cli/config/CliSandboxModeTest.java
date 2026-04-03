package org.dean.codex.cli.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CliSandboxModeTest {

    @Test
    void parsesSandboxModesCaseInsensitively() {
        assertEquals(CliSandboxMode.WORKSPACE_WRITE, CliSandboxMode.fromCliValue("workspace-write"));
        assertEquals(CliSandboxMode.WORKSPACE_WRITE, CliSandboxMode.fromCliValue("WORKSPACE_WRITE"));
        assertEquals(CliSandboxMode.READ_ONLY, CliSandboxMode.fromCliValue("read_only"));
        assertEquals(CliSandboxMode.FULL_ACCESS, CliSandboxMode.fromCliValue("FULL-ACCESS"));
    }

    @Test
    void returnsNullForBlankSandboxMode() {
        assertNull(CliSandboxMode.fromCliValue(" "));
        assertNull(CliSandboxMode.fromCliValue(null));
    }

    @Test
    void rejectsUnknownSandboxModes() {
        assertThrows(IllegalArgumentException.class, () -> CliSandboxMode.fromCliValue("workspace"));
    }
}
