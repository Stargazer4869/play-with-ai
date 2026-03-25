package org.dean.codex.tools.local;

import org.dean.codex.core.tool.local.CommandApprovalPolicy;
import org.dean.codex.core.tool.local.ShellCommandTool;
import org.dean.codex.protocol.tool.CommandApproval;
import org.dean.codex.protocol.tool.CommandApprovalDecision;
import org.dean.codex.protocol.tool.ShellCommandResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Component
public class ShellCommandToolImpl implements ShellCommandTool {

    private static final Logger logger = LoggerFactory.getLogger(ShellCommandToolImpl.class);
    private static final int MAX_COMMAND_LOG_LENGTH = 160;

    private final Path workspaceRoot;
    private final CommandApprovalPolicy commandApprovalPolicy;
    private final Duration commandTimeout;

    public ShellCommandToolImpl(@Qualifier("codexWorkspaceRoot") Path workspaceRoot,
                                CommandApprovalPolicy commandApprovalPolicy,
                                @Qualifier("codexCommandTimeout") Duration commandTimeout) {
        this.workspaceRoot = workspaceRoot;
        this.commandApprovalPolicy = commandApprovalPolicy;
        this.commandTimeout = commandTimeout;
    }

    @Override
    @Tool(description = "Run a zsh shell command from the workspace root. Safe inspection and verification commands may run automatically, while sensitive commands can be returned as approval-required or blocked by policy.")
    public ShellCommandResult runCommand(String command) {
        logger.info("Copilot tool runCommand used with command={} in {}", summarizeCommand(command), workspaceRoot);
        if (command == null || command.isBlank()) {
            return new ShellCommandResult(
                    false,
                    "",
                    -1,
                    "",
                    "",
                    false,
                    workspaceRoot.toString(),
                    false,
                    CommandApprovalDecision.BLOCK,
                    "Command must not be blank.",
                    "Command must not be blank.");
        }

        CommandApproval approval = commandApprovalPolicy.evaluate(command);
        if (approval.decision() == CommandApprovalDecision.BLOCK) {
            return new ShellCommandResult(
                    false,
                    command,
                    -1,
                    "",
                    "",
                    false,
                    workspaceRoot.toString(),
                    false,
                    approval.decision(),
                    approval.reason(),
                    "Command blocked by approval policy.");
        }
        if (approval.decision() == CommandApprovalDecision.REQUIRE_APPROVAL) {
            return new ShellCommandResult(
                    false,
                    command,
                    -1,
                    "",
                    "",
                    false,
                    workspaceRoot.toString(),
                    false,
                    approval.decision(),
                    approval.reason(),
                    "Command requires approval before execution.");
        }

        return executeCommand(command, approval);
    }

    @Override
    public ShellCommandResult runApprovedCommand(String command) {
        logger.info("Codex approved shell execution for command={} in {}", summarizeCommand(command), workspaceRoot);
        if (command == null || command.isBlank()) {
            return new ShellCommandResult(
                    false,
                    "",
                    -1,
                    "",
                    "",
                    false,
                    workspaceRoot.toString(),
                    false,
                    CommandApprovalDecision.BLOCK,
                    "Command must not be blank.",
                    "Command must not be blank.");
        }

        return executeCommand(command, new CommandApproval(CommandApprovalDecision.ALLOW, "Explicitly approved from CLI."));
    }

    private ShellCommandResult executeCommand(String command, CommandApproval approval) {
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            Process process = new ProcessBuilder("zsh", "-lc", command)
                    .directory(workspaceRoot.toFile())
                    .start();

            Future<String> stdoutFuture = executorService.submit(() -> readStream(process.getInputStream()));
            Future<String> stderrFuture = executorService.submit(() -> readStream(process.getErrorStream()));

            boolean finished = process.waitFor(commandTimeout.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return new ShellCommandResult(
                        false,
                        command,
                        -1,
                        getFutureValue(stdoutFuture),
                        getFutureValue(stderrFuture),
                        true,
                        workspaceRoot.toString(),
                        true,
                        approval.decision(),
                        approval.reason(),
                        "Command timed out after %d seconds.".formatted(commandTimeout.toSeconds()));
            }

            int exitCode = process.exitValue();
            String stdout = getFutureValue(stdoutFuture);
            String stderr = getFutureValue(stderrFuture);

            return new ShellCommandResult(
                    exitCode == 0,
                    command,
                    exitCode,
                    stdout,
                    stderr,
                    false,
                    workspaceRoot.toString(),
                    true,
                    approval.decision(),
                    approval.reason(),
                    exitCode == 0 ? "" : "Command exited with a non-zero status.");
        }
        catch (Exception exception) {
            return new ShellCommandResult(
                    false,
                    command,
                    -1,
                    "",
                    "",
                    false,
                    workspaceRoot.toString(),
                    true,
                    approval.decision(),
                    approval.reason(),
                    exception.getMessage());
        }
        finally {
            executorService.shutdownNow();
        }
    }

    private String summarizeCommand(String command) {
        if (command == null || command.isBlank()) {
            return "<blank>";
        }
        String normalized = command.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_COMMAND_LOG_LENGTH
                ? normalized
                : normalized.substring(0, MAX_COMMAND_LOG_LENGTH) + "…";
    }

    private String getFutureValue(Future<String> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        }
        catch (Exception exception) {
            return "";
        }
    }

    private String readStream(InputStream stream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!output.isEmpty()) {
                    output.append(System.lineSeparator());
                }
                output.append(line);
            }
            return output.toString();
        }
    }
}
