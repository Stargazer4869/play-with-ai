package org.dean.codex.tools.local;

import org.dean.codex.protocol.tool.FileReadResult;
import org.dean.codex.protocol.tool.FilePatchResult;
import org.dean.codex.protocol.tool.FileSearchResult;
import org.dean.codex.protocol.tool.FileWriteResult;
import org.dean.codex.protocol.tool.CommandApprovalDecision;
import org.dean.codex.protocol.tool.ShellCommandResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalToolsTest {

    @TempDir
    Path workspaceRoot;

    private FileReaderToolImpl fileReaderTool;
    private FileWriterToolImpl fileWriterTool;
    private ShellCommandToolImpl shellCommandTool;
    private FileSearchToolImpl fileSearchTool;
    private FilePatchToolImpl filePatchTool;

    @BeforeEach
    void setUp() {
        fileReaderTool = new FileReaderToolImpl(workspaceRoot);
        fileWriterTool = new FileWriterToolImpl(workspaceRoot);
        shellCommandTool = new ShellCommandToolImpl(
                workspaceRoot,
                new PatternCommandApprovalPolicy(PatternCommandApprovalPolicy.Mode.REVIEW_SENSITIVE),
                java.time.Duration.ofSeconds(60));
        fileSearchTool = new FileSearchToolImpl(workspaceRoot);
        filePatchTool = new FilePatchToolImpl(workspaceRoot);
    }

    @Test
    void readFileReturnsContentForWorkspaceFile() throws Exception {
        Files.writeString(workspaceRoot.resolve("notes.txt"), "hello world");

        FileReadResult result = fileReaderTool.readFile("notes.txt");

        assertTrue(result.success());
        assertEquals("notes.txt", result.path());
        assertEquals("hello world", result.content());
        assertFalse(result.truncated());
        assertEquals(11, result.totalCharacters());
        assertEquals("", result.error());
    }

    @Test
    void readFileRejectsPathTraversal() {
        FileReadResult result = fileReaderTool.readFile("../outside.txt");

        assertFalse(result.success());
        assertTrue(result.error().contains("project root"));
    }

    @Test
    void readFileTruncatesLargeContent() throws Exception {
        String largeContent = "a".repeat(12_500);
        Files.writeString(workspaceRoot.resolve("large.txt"), largeContent);

        FileReadResult result = fileReaderTool.readFile("large.txt");

        assertTrue(result.success());
        assertTrue(result.truncated());
        assertEquals(12_000, result.content().length());
        assertEquals(12_500, result.totalCharacters());
        assertTrue(result.error().contains("truncated"));
    }

    @Test
    void writeFileCreatesParentsAndOverwritesExistingContent() throws Exception {
        FileWriteResult created = fileWriterTool.writeFile("nested/demo.txt", "first");
        FileWriteResult updated = fileWriterTool.writeFile("nested/demo.txt", "second");

        assertTrue(created.success());
        assertTrue(created.created());
        assertEquals(5, created.charactersWritten());
        assertTrue(updated.success());
        assertFalse(updated.created());
        assertEquals("second", Files.readString(workspaceRoot.resolve("nested/demo.txt")));
    }

    @Test
    void writeFileRejectsPathTraversal() {
        FileWriteResult result = fileWriterTool.writeFile("../../escape.txt", "nope");

        assertFalse(result.success());
        assertTrue(result.error().contains("project root"));
    }

    @Test
    void runCommandCapturesStdoutAndWorkingDirectory() {
        ShellCommandResult result = shellCommandTool.runCommand("printf 'hi from shell'");

        assertTrue(result.success());
        assertEquals(0, result.exitCode());
        assertEquals("hi from shell", result.stdout());
        assertEquals("", result.stderr());
        assertFalse(result.timedOut());
        assertEquals(workspaceRoot.toString(), result.workingDirectory());
        assertTrue(result.executed());
        assertEquals(CommandApprovalDecision.ALLOW, result.approvalDecision());
    }

    @Test
    void runCommandReportsNonZeroExitCode() {
        ShellCommandResult result = shellCommandTool.runCommand("echo boom >&2; exit 7");

        assertFalse(result.success());
        assertEquals(7, result.exitCode());
        assertTrue(result.stderr().contains("boom"));
        assertFalse(result.timedOut());
        assertTrue(result.error().contains("non-zero"));
        assertTrue(result.executed());
    }

    @Test
    void runCommandRejectsBlankCommand() {
        ShellCommandResult result = shellCommandTool.runCommand("   ");

        assertFalse(result.success());
        assertEquals(-1, result.exitCode());
        assertTrue(result.error().contains("must not be blank"));
        assertFalse(result.executed());
        assertEquals(CommandApprovalDecision.BLOCK, result.approvalDecision());
    }

    @Test
    void runCommandRequiresApprovalForSensitiveMutation() {
        ShellCommandResult result = shellCommandTool.runCommand("git commit -m 'ship it'");

        assertFalse(result.success());
        assertEquals(-1, result.exitCode());
        assertFalse(result.executed());
        assertEquals(CommandApprovalDecision.REQUIRE_APPROVAL, result.approvalDecision());
        assertTrue(result.error().contains("requires approval"));
    }

    @Test
    void searchFindsMatchingLinesInsideWorkspace() throws Exception {
        Files.createDirectories(workspaceRoot.resolve("src"));
        Files.writeString(workspaceRoot.resolve("src/demo.txt"), "alpha\nbeta\ngamma beta");

        FileSearchResult result = fileSearchTool.search("beta", "");

        assertTrue(result.success());
        assertEquals("beta", result.query());
        assertEquals(2, result.totalMatches());
        assertEquals(2, result.matches().size());
        assertEquals("src/demo.txt", result.matches().get(0).path());
        assertEquals(2, result.matches().get(0).lineNumber());
    }

    @Test
    void searchRejectsScopeOutsideWorkspace() {
        FileSearchResult result = fileSearchTool.search("beta", "../outside");

        assertFalse(result.success());
        assertTrue(result.error().contains("workspace root"));
    }

    @Test
    void patchAppliesSingleExactReplacement() throws Exception {
        Files.createDirectories(workspaceRoot.resolve("src"));
        Files.writeString(workspaceRoot.resolve("src/App.java"), "class App { String name = \"old\"; }");

        FilePatchResult result = filePatchTool.applyPatch("src/App.java", "\"old\"", "\"new\"", false);

        assertTrue(result.success());
        assertEquals(1, result.replacements());
        assertEquals("class App { String name = \"new\"; }", Files.readString(workspaceRoot.resolve("src/App.java")));
    }

    @Test
    void patchRejectsAmbiguousSingleReplace() throws Exception {
        Files.createDirectories(workspaceRoot.resolve("src"));
        Files.writeString(workspaceRoot.resolve("src/App.java"), "old old");

        FilePatchResult result = filePatchTool.applyPatch("src/App.java", "old", "new", false);

        assertFalse(result.success());
        assertTrue(result.error().contains("multiple locations"));
    }

}
