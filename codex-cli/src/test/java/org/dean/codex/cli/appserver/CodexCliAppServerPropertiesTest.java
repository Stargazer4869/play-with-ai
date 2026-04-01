package org.dean.codex.cli.appserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexCliAppServerPropertiesTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvedCommandUsesDirectClasspathLaunchOutsideExecutableJar() {
        CodexCliAppServerProperties properties = new CodexCliAppServerProperties();
        String classPath = tempDir.resolve("classes").toString()
                + File.pathSeparator
                + tempDir.resolve("dependency.jar");

        withSystemProperties("/tmp/fake-jre", classPath, () -> {
            List<String> command = properties.resolvedCommand();

            assertTrue(command.get(0).endsWith("/bin/java"));
            assertEquals("-cp", command.get(1));
            assertEquals(classPath, command.get(2));
            assertEquals("org.dean.codex.runtime.springai.appserver.CodexAppServerStdioApplication", command.get(3));
            assertEquals("--codex.cli.enabled=false", command.get(4));
        });
    }

    @Test
    void resolvedCommandUsesPropertiesLauncherForExecutableSpringBootJar() throws IOException {
        CodexCliAppServerProperties properties = new CodexCliAppServerProperties();
        Path executableJar = tempDir.resolve("codex-cli.jar");
        createExecutableSpringBootJar(executableJar);

        withSystemProperties("/tmp/fake-jre", executableJar.toString(), () -> {
            List<String> command = properties.resolvedCommand();

            assertTrue(command.get(0).endsWith("/bin/java"));
            assertEquals("-Dloader.main=org.dean.codex.runtime.springai.appserver.CodexAppServerStdioApplication", command.get(1));
            assertEquals("-cp", command.get(2));
            assertEquals(executableJar.toAbsolutePath().toString(), command.get(3));
            assertEquals("org.springframework.boot.loader.launch.PropertiesLauncher", command.get(4));
            assertEquals("--codex.cli.enabled=false", command.get(5));
            assertFalse(command.stream().anyMatch(token ->
                    token.equals("org.dean.codex.runtime.springai.appserver.CodexAppServerStdioApplication")));
        });
    }

    private void createExecutableSpringBootJar(Path jarPath) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, "org.springframework.boot.loader.launch.JarLauncher");
        attributes.putValue("Spring-Boot-Classes", "BOOT-INF/classes/");

        try (OutputStream outputStream = java.nio.file.Files.newOutputStream(jarPath);
             JarOutputStream jarOutputStream = new JarOutputStream(outputStream, manifest)) {
            // The manifest is enough for command resolution.
        }
    }

    private void withSystemProperties(String javaHome, String classPath, ThrowingRunnable action) {
        String originalJavaHome = System.getProperty("java.home");
        String originalClassPath = System.getProperty("java.class.path");
        try {
            System.setProperty("java.home", javaHome);
            System.setProperty("java.class.path", classPath);
            action.run();
        }
        catch (Exception exception) {
            throw new RuntimeException(exception);
        }
        finally {
            restoreProperty("java.home", originalJavaHome);
            restoreProperty("java.class.path", originalClassPath);
        }
    }

    private void restoreProperty(String propertyName, String value) {
        if (value == null) {
            System.clearProperty(propertyName);
            return;
        }
        System.setProperty(propertyName, value);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
