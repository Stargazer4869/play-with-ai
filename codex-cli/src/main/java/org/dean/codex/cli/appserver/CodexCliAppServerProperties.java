package org.dean.codex.cli.appserver;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;

@ConfigurationProperties(prefix = "codex.cli.app-server")
public class CodexCliAppServerProperties {

    private static final String CLI_ENABLED_OVERRIDE = "--codex.cli.enabled=false";
    private static final String ROOT_LOGGING_OVERRIDE = "--logging.level.root=OFF";
    private static final String CODEX_LOGGING_OVERRIDE = "--logging.level.org.dean.codex=OFF";
    private static final String SIMPLE_LOGGER_ADVISOR_OVERRIDE = "--logging.level.org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor=OFF";
    private static final String SPRING_BOOT_JAR_LAUNCHER = "org.springframework.boot.loader.launch.JarLauncher";

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

        Optional<String> executableJar = executableSpringBootJar(classPath);
        if (executableJar.isPresent()) {
            return executableJarCommand(javaBinary, executableJar.orElseThrow());
        }

        return standardClassPathCommand(javaBinary, classPath);
    }

    private List<String> standardClassPathCommand(String javaBinary, String classPath) {
        List<String> generatedCommand = new ArrayList<>();
        generatedCommand.add(javaBinary);
        generatedCommand.add("-cp");
        generatedCommand.add(classPath);
        generatedCommand.add(mainClass);
        generatedCommand.add(CLI_ENABLED_OVERRIDE);
        generatedCommand.add("--spring.main.banner-mode=off");
        generatedCommand.add("--spring.main.log-startup-info=false");
        generatedCommand.add(ROOT_LOGGING_OVERRIDE);
        generatedCommand.add(CODEX_LOGGING_OVERRIDE);
        generatedCommand.add(SIMPLE_LOGGER_ADVISOR_OVERRIDE);
        return List.copyOf(generatedCommand);
    }

    private List<String> executableJarCommand(String javaBinary, String jarPath) {
        List<String> generatedCommand = new ArrayList<>();
        generatedCommand.add(javaBinary);
        generatedCommand.add("-jar");
        generatedCommand.add(jarPath);
        generatedCommand.add(CLI_ENABLED_OVERRIDE);
        generatedCommand.add("--spring.main.banner-mode=off");
        generatedCommand.add("--spring.main.log-startup-info=false");
        generatedCommand.add(ROOT_LOGGING_OVERRIDE);
        generatedCommand.add(CODEX_LOGGING_OVERRIDE);
        generatedCommand.add(SIMPLE_LOGGER_ADVISOR_OVERRIDE);
        return List.copyOf(generatedCommand);
    }

    private Optional<String> executableSpringBootJar(String classPath) {
        String[] classPathEntries = classPath.split(java.util.regex.Pattern.quote(File.pathSeparator));
        if (classPathEntries.length != 1) {
            return Optional.empty();
        }

        String candidate = classPathEntries[0];
        if (candidate == null || candidate.isBlank()) {
            return Optional.empty();
        }

        File file = new File(candidate);
        if (!file.isFile() || !candidate.endsWith(".jar")) {
            return Optional.empty();
        }

        return isExecutableSpringBootJar(file) ? Optional.of(file.getAbsolutePath()) : Optional.empty();
    }

    private boolean isExecutableSpringBootJar(File jarFile) {
        try (JarFile candidate = new JarFile(jarFile)) {
            var manifest = candidate.getManifest();
            if (manifest == null) {
                return false;
            }
            String mainClassName = manifest.getMainAttributes().getValue("Main-Class");
            String bootClassesLocation = manifest.getMainAttributes().getValue("Spring-Boot-Classes");
            return SPRING_BOOT_JAR_LAUNCHER.equals(mainClassName)
                    && bootClassesLocation != null
                    && !bootClassesLocation.isBlank();
        }
        catch (IOException ignored) {
            return false;
        }
    }
}
