package org.dean.codex.cli.launch;

import org.junit.jupiter.api.Test;

import org.dean.codex.cli.config.CliApprovalMode;
import org.dean.codex.cli.config.CliConfigOverrides;
import org.dean.codex.cli.config.CliConfigOverridesMapper;
import org.dean.codex.cli.config.CliSandboxMode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliLaunchRequestTest {

    @Test
    void copiesArgumentsAndExposesAnImmutableView() {
        String[] rawArgs = {"resume", "--last"};
        CliConfigOverrides overrides = new CliConfigOverrides("gpt-5.4", "./workspace", CliSandboxMode.WORKSPACE_WRITE, CliApprovalMode.REVIEW_SENSITIVE);

        CliLaunchRequest request = CliLaunchRequest.of(rawArgs, overrides);
        rawArgs[0] = "fork";

        assertEquals(List.of("resume", "--last"), request.arguments());
        assertEquals(List.of("resume", "--last"), request.argumentsView());
        assertArrayEquals(new String[]{"resume", "--last"}, request.toArray());
        assertFalse(request.isEmpty());
        assertEquals(overrides, request.configOverrides());

        assertThrows(UnsupportedOperationException.class, () -> request.arguments().add("unexpected"));
    }

    @Test
    void defaultsToAnEmptyRequestWhenNoArgumentsAreProvided() {
        CliLaunchRequest request = CliLaunchRequest.of();

        assertTrue(request.isEmpty());
        assertEquals(List.of(), request.arguments());
        assertEquals(CliConfigOverridesMapper.empty(), request.configOverrides());
    }
}
