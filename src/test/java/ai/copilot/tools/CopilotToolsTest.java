package ai.copilot.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CopilotToolsTest {

    @TempDir
    Path workspaceRoot;

    private FileReaderToolImpl fileReaderTool;
    private FileWriterToolImpl fileWriterTool;
    private ShellCommandToolImpl shellCommandTool;

    @BeforeEach
    void setUp() {
        fileReaderTool = new FileReaderToolImpl(workspaceRoot);
        fileWriterTool = new FileWriterToolImpl(workspaceRoot);
        shellCommandTool = new ShellCommandToolImpl(workspaceRoot);
    }

    @Test
    void readFileReturnsContentForWorkspaceFile() throws Exception {
        Files.writeString(workspaceRoot.resolve("notes.txt"), "hello world");

        FileReaderTool.FileReadResult result = fileReaderTool.readFile("notes.txt");

        assertTrue(result.success());
        assertEquals("notes.txt", result.path());
        assertEquals("hello world", result.content());
        assertFalse(result.truncated());
        assertEquals(11, result.totalCharacters());
        assertEquals("", result.error());
    }

    @Test
    void readFileRejectsPathTraversal() {
        FileReaderTool.FileReadResult result = fileReaderTool.readFile("../outside.txt");

        assertFalse(result.success());
        assertTrue(result.error().contains("project root"));
    }

    @Test
    void readFileTruncatesLargeContent() throws Exception {
        String largeContent = "a".repeat(12_500);
        Files.writeString(workspaceRoot.resolve("large.txt"), largeContent);

        FileReaderTool.FileReadResult result = fileReaderTool.readFile("large.txt");

        assertTrue(result.success());
        assertTrue(result.truncated());
        assertEquals(12_000, result.content().length());
        assertEquals(12_500, result.totalCharacters());
        assertTrue(result.error().contains("truncated"));
    }

    @Test
    void writeFileCreatesParentsAndOverwritesExistingContent() throws Exception {
        FileWriterTool.FileWriteResult created = fileWriterTool.writeFile("nested/demo.txt", "first");
        FileWriterTool.FileWriteResult updated = fileWriterTool.writeFile("nested/demo.txt", "second");

        assertTrue(created.success());
        assertTrue(created.created());
        assertEquals(5, created.charactersWritten());
        assertTrue(updated.success());
        assertFalse(updated.created());
        assertEquals("second", Files.readString(workspaceRoot.resolve("nested/demo.txt")));
    }

    @Test
    void writeFileRejectsPathTraversal() {
        FileWriterTool.FileWriteResult result = fileWriterTool.writeFile("../../escape.txt", "nope");

        assertFalse(result.success());
        assertTrue(result.error().contains("project root"));
    }

    @Test
    void runCommandCapturesStdoutAndWorkingDirectory() {
        ShellCommandTool.CommandResult result = shellCommandTool.runCommand("printf 'hi from shell'");

        assertTrue(result.success());
        assertEquals(0, result.exitCode());
        assertEquals("hi from shell", result.stdout());
        assertEquals("", result.stderr());
        assertFalse(result.timedOut());
        assertEquals(workspaceRoot.toString(), result.workingDirectory());
    }

    @Test
    void runCommandReportsNonZeroExitCode() {
        ShellCommandTool.CommandResult result = shellCommandTool.runCommand("echo boom >&2; exit 7");

        assertFalse(result.success());
        assertEquals(7, result.exitCode());
        assertTrue(result.stderr().contains("boom"));
        assertFalse(result.timedOut());
        assertTrue(result.error().contains("non-zero"));
    }

    @Test
    void runCommandRejectsBlankCommand() {
        ShellCommandTool.CommandResult result = shellCommandTool.runCommand("   ");

        assertFalse(result.success());
        assertEquals(-1, result.exitCode());
        assertTrue(result.error().contains("must not be blank"));
    }
}

