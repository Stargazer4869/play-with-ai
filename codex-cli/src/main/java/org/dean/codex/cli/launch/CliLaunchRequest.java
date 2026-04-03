package org.dean.codex.cli.launch;

import org.dean.codex.cli.config.CliConfigOverrides;
import org.dean.codex.cli.config.CliConfigOverridesMapper;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record CliLaunchRequest(List<String> arguments, CliConfigOverrides configOverrides) {
    public CliLaunchRequest {
        arguments = List.copyOf(Objects.requireNonNullElse(arguments, List.of()));
        configOverrides = configOverrides == null ? CliConfigOverridesMapper.empty() : configOverrides;
    }

    public static CliLaunchRequest of(String... args) {
        return of(args, CliConfigOverridesMapper.empty());
    }

    public static CliLaunchRequest of(String[] args, CliConfigOverrides configOverrides) {
        if (args == null || args.length == 0) {
            return new CliLaunchRequest(List.of(), configOverrides);
        }
        return new CliLaunchRequest(Arrays.asList(args.clone()), configOverrides);
    }

    public List<String> argumentsView() {
        return Collections.unmodifiableList(arguments);
    }

    public String[] toArray() {
        return arguments.toArray(String[]::new);
    }

    public boolean isEmpty() {
        return arguments.isEmpty();
    }
}
