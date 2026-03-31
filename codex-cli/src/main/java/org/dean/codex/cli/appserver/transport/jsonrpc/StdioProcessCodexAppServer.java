package org.dean.codex.cli.appserver.transport.jsonrpc;

import org.dean.codex.cli.appserver.CodexCliAppServerProperties;
import org.dean.codex.core.appserver.CodexAppServer;
import org.dean.codex.core.appserver.CodexAppServerSession;

import java.io.IOException;
import java.time.Duration;

public class StdioProcessCodexAppServer implements CodexAppServer {

    private final CodexCliAppServerProperties properties;

    public StdioProcessCodexAppServer(CodexCliAppServerProperties properties) {
        this.properties = properties;
    }

    @Override
    public CodexAppServerSession connect() {
        ProcessBuilder processBuilder = new ProcessBuilder(properties.resolvedCommand());
        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
        try {
            Process process = processBuilder.start();
            return new JsonRpcCodexAppServerSession(
                    process.getInputStream(),
                    process.getOutputStream(),
                    () -> closeProcess(process),
                    Duration.ofSeconds(Math.max(1, properties.getRequestTimeoutSeconds())));
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to launch app-server process.", exception);
        }
    }

    private void closeProcess(Process process) throws Exception {
        try {
            process.getOutputStream().close();
        }
        catch (Exception ignored) {
            // Ignore cleanup failures.
        }
        try {
            process.getInputStream().close();
        }
        catch (Exception ignored) {
            // Ignore cleanup failures.
        }
        try {
            process.getErrorStream().close();
        }
        catch (Exception ignored) {
            // Ignore cleanup failures.
        }

        if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroy();
            if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
    }
}
