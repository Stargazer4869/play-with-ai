package ai.copilot.tools;

public interface ShellCommandTool {

    CommandResult runCommand(String command);

    record CommandResult(boolean success,
                         String command,
                         int exitCode,
                         String stdout,
                         String stderr,
                         boolean timedOut,
                         String workingDirectory,
                         String error) {
    }
}
