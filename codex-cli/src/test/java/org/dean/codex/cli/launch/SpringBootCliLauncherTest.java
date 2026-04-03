package org.dean.codex.cli.launch;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SpringBootCliLauncherTest {

    @Test
    void delegatesLaunchToTheConfiguredApplicationStartup() {
        AtomicReference<CliLaunchRequest> seen = new AtomicReference<>();
        CliApplicationStartup startup = seen::set;
        SpringBootCliLauncher launcher = new SpringBootCliLauncher(startup);
        CliLaunchRequest request = CliLaunchRequest.of("--help");

        launcher.launch(request);

        assertSame(request, seen.get());
        assertEquals(request, seen.get());
    }
}
