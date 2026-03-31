package org.dean.codex.cli.appserver;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "codex.cli.app-server")
public class CodexCliAppServerProperties {

    private List<String> command = List.of();
    private String mainClass = "org.dean.codex.runtime.springai.appserver.CodexAppServerStdioApplication";
    private long requestTimeoutSeconds = 30;

    public List<String> getCommand() {
        return List.copyOf(command);
    }

    public void setCommand(List<String> command) {
        this.command = command == null ? List.of() : List.copyOf(command);
    }

    public String getMainClass() {
        return mainClass;
    }

    public void setMainClass(String mainClass) {
        this.mainClass = mainClass;
    }

    public long getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(long requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public List<String> resolvedCommand() {
        if (!command.isEmpty()) {
            return command;
        }
        String javaHome = System.getProperty("java.home", "");
        String javaBinary = javaHome.isBlank()
                ? "java"
                : javaHome + "/bin/java";
        String classPath = System.getProperty("java.class.path", "");
        if (mainClass == null || mainClass.isBlank()) {
            throw new IllegalStateException("codex.cli.app-server.main-class must not be blank.");
        }
        if (classPath.isBlank()) {
            throw new IllegalStateException("java.class.path is empty; unable to launch app-server child process.");
        }

        List<String> generatedCommand = new ArrayList<>();
        generatedCommand.add(javaBinary);
        generatedCommand.add("-cp");
        generatedCommand.add(classPath);
        generatedCommand.add(mainClass);
        generatedCommand.add("--spring.main.banner-mode=off");
        generatedCommand.add("--spring.main.log-startup-info=false");
        generatedCommand.add("--logging.level.root=OFF");
        return List.copyOf(generatedCommand);
    }
}
