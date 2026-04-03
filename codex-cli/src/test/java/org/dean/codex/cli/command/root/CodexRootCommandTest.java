package org.dean.codex.cli.command.root;

import org.junit.jupiter.api.Test;
import org.dean.codex.cli.config.CliApprovalMode;
import org.dean.codex.cli.config.CliConfigOverrides;
import org.dean.codex.cli.config.CliSandboxMode;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexRootCommandTest {

    @Test
    void rootCommandRegistersInitialTopLevelSubcommands() {
        CommandLine commandLine = CodexRootCommandLine.create();

        Map<String, CommandLine> subcommands = commandLine.getSubcommands();

        assertEquals(
                List.of("exec", "review", "login", "logout", "completion", "sandbox", "resume", "fork"),
                List.copyOf(subcommands.keySet())
        );
    }

    @Test
    void rootHelpMentionsCodexAndTheInitialSubcommands() {
        String usage = CodexRootCommandLine.create().getUsageMessage();

        assertTrue(usage.contains("Usage: codex"));
        assertTrue(usage.contains("exec"));
        assertTrue(usage.contains("review"));
        assertTrue(usage.contains("login"));
        assertTrue(usage.contains("logout"));
        assertTrue(usage.contains("completion"));
        assertTrue(usage.contains("sandbox"));
        assertTrue(usage.contains("resume"));
        assertTrue(usage.contains("fork"));
        assertTrue(usage.contains("--model"));
        assertTrue(usage.contains("--cd"));
        assertTrue(usage.contains("--sandbox"));
        assertTrue(usage.contains("--approval-mode"));
    }

    @Test
    void subcommandHelpIsAvailableForResumeAndFork() {
        assertTrue(renderUsage("resume", "--help").contains("Usage: codex resume"));
        assertTrue(renderUsage("fork", "--help").contains("Usage: codex fork"));
    }

    @Test
    void rootCommandCapturesOptionalPromptTokensWhenNoSubcommandIsPresent() {
        CodexRootCommand rootCommand = new CodexRootCommand();
        CommandLine commandLine = new CommandLine(rootCommand);

        commandLine.parseArgs("plan", "the", "next", "step");

        assertEquals(List.of("plan", "the", "next", "step"), rootCommand.getPromptTokens());
        assertEquals("plan the next step", rootCommand.getPromptText());
    }

    @Test
    void rootCommandCapturesSharedLaunchOverrides() {
        CodexRootCommand rootCommand = new CodexRootCommand();
        CommandLine commandLine = new CommandLine(rootCommand);

        commandLine.parseArgs(
                "--model", "gpt-5.4",
                "--cd", "./workspace",
                "--sandbox", "workspace-write",
                "--approval-mode", "review-sensitive",
                "plan");

        CliConfigOverrides overrides = rootCommand.getConfigOverrides();
        assertEquals("gpt-5.4", overrides.model());
        assertEquals("./workspace", overrides.cd());
        assertEquals(CliSandboxMode.WORKSPACE_WRITE, overrides.sandbox());
        assertEquals(CliApprovalMode.REVIEW_SENSITIVE, overrides.approvalMode());
    }

    private String renderUsage(String... args) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CommandLine commandLine = CodexRootCommandLine.create();
        commandLine.setOut(new PrintWriter(outputStream, true));
        commandLine.execute(args);
        return outputStream.toString(StandardCharsets.UTF_8);
    }
}
