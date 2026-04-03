package org.dean.codex.cli.command.root;

import org.dean.codex.cli.config.CliConfigOverrides;
import org.dean.codex.cli.config.CliConfigOverridesMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;

@Command(
        name = "codex",
        description = "Codex command-line interface root command.",
        mixinStandardHelpOptions = true,
        sortOptions = false,
        subcommands = {
                CodexRootCommand.ExecCommand.class,
                CodexRootCommand.ReviewCommand.class,
                CodexRootCommand.LoginCommand.class,
                CodexRootCommand.LogoutCommand.class,
                CodexRootCommand.CompletionCommand.class,
                CodexRootCommand.SandboxCommand.class,
                CodexRootCommand.ResumeCommand.class,
                CodexRootCommand.ForkCommand.class
        }
)
public class CodexRootCommand implements Runnable {

    @Parameters(
            arity = "0..*",
            description = "Optional prompt to execute when no subcommand is supplied."
    )
    private List<String> promptTokens = new ArrayList<>();

    @Option(names = "--model", description = "Override the model for this launch.")
    private String model;

    @Option(names = "--cd", description = "Change to this directory before launch.")
    private String cd;

    @Option(names = "--sandbox", description = "Override the sandbox mode for this launch.")
    private String sandbox;

    @Option(names = "--approval-mode", description = "Override the approval mode for this launch.")
    private String approvalMode;

    @Override
    public void run() {
        // Placeholder for the interactive launch path. Issue 3 will wire execution.
    }

    public List<String> getPromptTokens() {
        return List.copyOf(promptTokens);
    }

    public String getPromptText() {
        return String.join(" ", promptTokens);
    }

    public CliConfigOverrides getConfigOverrides() {
        return CliConfigOverridesMapper.fromRawValues(model, cd, sandbox, approvalMode);
    }

    public abstract static class ArgumentCaptureCommand implements Runnable {
        @Parameters(
                arity = "0..*",
                description = "Additional command arguments."
        )
        private List<String> arguments = new ArrayList<>();

        @Override
        public void run() {
            // The runtime dispatch path handles execution after picocli parsing succeeds.
        }

        public List<String> arguments() {
            return List.copyOf(arguments);
        }
    }

    @Command(
            name = "exec",
            description = "Execute a prompt non-interactively.",
            mixinStandardHelpOptions = true
    )
    public static final class ExecCommand extends ArgumentCaptureCommand {
    }

    @Command(
            name = "review",
            description = "Start a review session.",
            mixinStandardHelpOptions = true
    )
    public static final class ReviewCommand extends ArgumentCaptureCommand {
    }

    @Command(
            name = "login",
            description = "Authenticate with Codex.",
            mixinStandardHelpOptions = true
    )
    public static final class LoginCommand extends ArgumentCaptureCommand {
    }

    @Command(
            name = "logout",
            description = "Clear stored authentication.",
            mixinStandardHelpOptions = true
    )
    public static final class LogoutCommand extends ArgumentCaptureCommand {
    }

    @Command(
            name = "completion",
            description = "Generate shell completion scripts.",
            mixinStandardHelpOptions = true
    )
    public static final class CompletionCommand extends ArgumentCaptureCommand {
    }

    @Command(
            name = "sandbox",
            description = "Inspect or configure sandbox settings.",
            mixinStandardHelpOptions = true
    )
    public static final class SandboxCommand extends ArgumentCaptureCommand {
    }

    @Command(
            name = "resume",
            description = "Resume a previous thread.",
            mixinStandardHelpOptions = true
    )
    public static final class ResumeCommand extends ArgumentCaptureCommand {
    }

    @Command(
            name = "fork",
            description = "Fork an existing thread.",
            mixinStandardHelpOptions = true
    )
    public static final class ForkCommand extends ArgumentCaptureCommand {
    }
}
