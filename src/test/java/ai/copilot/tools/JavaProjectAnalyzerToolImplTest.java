package ai.copilot.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaProjectAnalyzerToolImplTest {

    @TempDir
    Path workspaceRoot;

    private JavaProjectAnalyzerToolImpl analyzerTool;

    @BeforeEach
    void setUp() {
        analyzerTool = new JavaProjectAnalyzerToolImpl(workspaceRoot);
    }

    @Test
    void analyzesCurrentProjectClassesWhenInputIsBlank() throws Exception {
        Path classesDirectory = workspaceRoot.resolve("target/classes");
        compileSampleClasses(classesDirectory);

        String summary = analyzerTool.analyzeProject("");

        assertTrue(summary.contains("org.example.alpha"));
        assertTrue(summary.contains("\tClassA"));
        assertTrue(summary.contains("public String name;"));
        assertTrue(summary.contains("public void setName(String name);"));
        assertTrue(summary.contains("\tSupportType"));
        assertTrue(summary.contains("org.example.beta"));
        assertTrue(summary.contains("public void printMySelf();"));
        assertFalse(summary.contains("InnerHelper"));
    }

    @Test
    void analyzesExternalClassesDirectoryFromAbsolutePath() throws Exception {
        Path compiledClasses = workspaceRoot.resolve("external-classes");
        compileSampleClasses(compiledClasses);

        String summary = analyzerTool.analyzeProject(compiledClasses.toAbsolutePath().toString());

        assertTrue(summary.contains("org.example.alpha"));
        assertTrue(summary.contains("\tClassA"));
        assertTrue(summary.contains("public void setAge(int age);"));
        assertTrue(summary.contains("org.example.beta"));
    }

    @Test
    void analyzesExternalJarFromAbsolutePath() throws Exception {
        Path compiledClasses = workspaceRoot.resolve("compiled");
        compileSampleClasses(compiledClasses);
        Path jarPath = workspaceRoot.resolve("sample.jar");
        createJarFromDirectory(compiledClasses, jarPath);

        String summary = analyzerTool.analyzeProject(jarPath.toAbsolutePath().toString());

        assertTrue(summary.contains("org.example.alpha"));
        assertTrue(summary.contains("\tClassA"));
        assertTrue(summary.contains("public int age;"));
        assertTrue(summary.contains("public String toString();"));
    }

    @Test
    void rejectsRelativePathInput() {
        String summary = analyzerTool.analyzeProject("relative/sample.jar");

        assertTrue(summary.contains("must be absolute"));
    }

    @Test
    void reportsMissingDefaultClassesDirectoryClearly() {
        String summary = analyzerTool.analyzeProject(null);

        assertTrue(summary.contains("Build the project first"));
    }

    private void compileSampleClasses(Path outputDirectory) throws IOException {
        Path sourceDirectory = workspaceRoot.resolve("sources");
        Files.createDirectories(sourceDirectory);

        Map<String, String> sources = Map.of(
                "org/example/alpha/ClassA.java", """
                        package org.example.alpha;
                        public class ClassA {
                            public String name;
                            public int age;
                            public String toString() { return name; }
                            public void setName(String name) { this.name = name; }
                            public void setAge(int age) { this.age = age; }
                            public static class InnerHelper {
                                public String hidden;
                            }
                        }
                        """,
                "org/example/alpha/SupportType.java", """
                        package org.example.alpha;
                        class SupportType {
                            public String code;
                            public void activate() { }
                        }
                        """,
                "org/example/beta/ClassB.java", """
                        package org.example.beta;
                        public class ClassB {
                            public String name;
                            public int age;
                            public String toString() { return name; }
                            public void setName(String name) { this.name = name; }
                            public void printMySelf() { }
                        }
                        """
        );

        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Path file = sourceDirectory.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler is not available.");
        }

        Files.createDirectories(outputDirectory);
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            List<Path> javaFiles;
            try (Stream<Path> stream = Files.walk(sourceDirectory)) {
                javaFiles = stream.filter(path -> path.toString().endsWith(".java")).toList();
            }
            Iterable<? extends JavaFileObject> fileObjects = fileManager.getJavaFileObjectsFromPaths(javaFiles);
            boolean success = compiler.getTask(null, fileManager, null,
                    List.of("-parameters", "-d", outputDirectory.toString()), null, fileObjects).call();
            if (!success) {
                throw new IllegalStateException("Failed to compile sample classes.");
            }
        }
    }

    private void createJarFromDirectory(Path classesDirectory, Path jarPath) throws IOException {
        try (JarOutputStream jarOutputStream = new JarOutputStream(Files.newOutputStream(jarPath));
             Stream<Path> stream = Files.walk(classesDirectory)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String entryName = classesDirectory.relativize(file).toString().replace('\\', '/');
                jarOutputStream.putNextEntry(new JarEntry(entryName));
                jarOutputStream.write(Files.readAllBytes(file));
                jarOutputStream.closeEntry();
            }
        }
    }
}
