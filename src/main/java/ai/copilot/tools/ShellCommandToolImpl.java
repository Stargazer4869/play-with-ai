package ai.copilot.tools;

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
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_COMMAND_LOG_LENGTH = 160;

    private final Path workspaceRoot;

    public ShellCommandToolImpl(@Qualifier("copilotWorkspaceRoot") Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    @Override
    @Tool(description = "Run a zsh shell command from the project root. Use this for safe project inspection, builds, tests, and verification. The command times out after 60 seconds.")
    public CommandResult runCommand(String command) {
        logger.info("Copilot tool runCommand used with command={} in {}", summarizeCommand(command), workspaceRoot);
        if (command == null || command.isBlank()) {
            return new CommandResult(false, "", -1, "", "", false, workspaceRoot.toString(), "Command must not be blank.");
        }

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            Process process = new ProcessBuilder("zsh", "-lc", command)
                    .directory(workspaceRoot.toFile())
                    .start();

            Future<String> stdoutFuture = executorService.submit(() -> readStream(process.getInputStream()));
            Future<String> stderrFuture = executorService.submit(() -> readStream(process.getErrorStream()));

            boolean finished = process.waitFor(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return new CommandResult(false,
                        command,
                        -1,
                        getFutureValue(stdoutFuture),
                        getFutureValue(stderrFuture),
                        true,
                        workspaceRoot.toString(),
                        "Command timed out after %d seconds.".formatted(COMMAND_TIMEOUT.toSeconds()));
            }

            int exitCode = process.exitValue();
            String stdout = getFutureValue(stdoutFuture);
            String stderr = getFutureValue(stderrFuture);

            return new CommandResult(exitCode == 0,
                    command,
                    exitCode,
                    stdout,
                    stderr,
                    false,
                    workspaceRoot.toString(),
                    exitCode == 0 ? "" : "Command exited with a non-zero status.");
        }
        catch (Exception exception) {
            return new CommandResult(false, command, -1, "", "", false, workspaceRoot.toString(), exception.getMessage());
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
