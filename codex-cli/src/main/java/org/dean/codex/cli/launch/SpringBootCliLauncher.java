package org.dean.codex.cli.launch;

import java.util.Objects;

public final class SpringBootCliLauncher implements CliLauncher {
    private final CliApplicationStartup applicationStartup;

    public SpringBootCliLauncher(CliApplicationStartup applicationStartup) {
        this.applicationStartup = Objects.requireNonNull(applicationStartup, "applicationStartup");
    }

    @Override
    public void launch(CliLaunchRequest request) {
        applicationStartup.start(Objects.requireNonNull(request, "request"));
    }
}
