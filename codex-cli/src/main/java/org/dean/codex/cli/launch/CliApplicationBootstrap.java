package org.dean.codex.cli.launch;

import org.dean.codex.cli.command.root.CodexRootCommand;
import org.dean.codex.cli.command.root.CodexRootCommandLine;

import picocli.CommandLine;

import java.util.Objects;

public final class CliApplicationBootstrap {
    private static final CliLauncher DEFAULT_LAUNCHER =
            new SpringBootCliLauncher(SpringBootCliApplicationStartup.codexCliApplication());

    private CliApplicationBootstrap() {
    }

    public static void launch(String... args) {
        CliLaunchRequest request = parseLaunchRequest(args);
        if (request == null) {
            return;
        }
        launch(request, DEFAULT_LAUNCHER);
    }

    static void launch(CliLaunchRequest request, CliLauncher launcher) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(launcher, "launcher").launch(request);
    }

    private static CliLaunchRequest parseLaunchRequest(String... args) {
        CommandLine commandLine = CodexRootCommandLine.create();
        try {
            CommandLine.ParseResult parseResult = commandLine.parseArgs(args);
            if (CommandLine.printHelpIfRequested(parseResult)) {
                return null;
            }
            CodexRootCommand rootCommand = (CodexRootCommand) commandLine.getCommand();
            return CliLaunchRequest.of(args, rootCommand.getConfigOverrides());
        }
        catch (CommandLine.ParameterException exception) {
            throw exception;
        }
    }
}
