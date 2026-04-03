package org.dean.codex.cli.launch;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CliApplicationBootstrapTest {

    @Test
    void delegatesTheLaunchRequestToTheProvidedLauncher() {
        CliLaunchRequest request = CliLaunchRequest.of("exec", "hello");
        AtomicReference<CliLaunchRequest> seen = new AtomicReference<>();

        CliLauncher launcher = seen::set;

        CliApplicationBootstrap.launch(request, launcher);

        assertSame(request, seen.get());
        assertEquals(request, seen.get());
    }
}
