package org.dean.codex.tools.local;

import org.dean.codex.core.tool.local.FileReaderTool;
import org.dean.codex.protocol.tool.FileReadResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class FileReaderToolImpl implements FileReaderTool {

    private static final Logger logger = LoggerFactory.getLogger(FileReaderToolImpl.class);
    private static final int MAX_CONTENT_CHARS = 12_000;

    private final Path workspaceRoot;

    public FileReaderToolImpl(@Qualifier("codexWorkspaceRoot") Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    @Override
    @Tool(description = "Read a UTF-8 text file relative to the project root. Use this before changing existing files. The path must stay inside the project root.")
    public FileReadResult readFile(String path) {
        logger.info("Copilot tool readFile used with path={}", summarizePath(path));
        try {
            Path target = resolvePath(path);
            if (!Files.exists(target)) {
                return new FileReadResult(false, workspaceRoot.relativize(target).toString(), "", false, 0, "File does not exist.");
            }
            if (Files.isDirectory(target)) {
                return new FileReadResult(false, workspaceRoot.relativize(target).toString(), "", false, 0, "Path points to a directory, not a file.");
            }

            String content = Files.readString(target);
            boolean truncated = content.length() > MAX_CONTENT_CHARS;
            String visibleContent = truncated ? content.substring(0, MAX_CONTENT_CHARS) : content;

            return new FileReadResult(true,
                    workspaceRoot.relativize(target).toString(),
                    visibleContent,
                    truncated,
                    content.length(),
                    truncated ? "Content was truncated to the first %d characters.".formatted(MAX_CONTENT_CHARS) : "");
        }
        catch (Exception exception) {
            return new FileReadResult(false, path, "", false, 0, exception.getMessage());
        }
    }

    private String summarizePath(String path) {
        return path == null || path.isBlank() ? "<blank>" : path;
    }

    private Path resolvePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path must not be blank.");
        }

        Path target = workspaceRoot.resolve(path).normalize();
        if (!target.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Path must stay inside the project root.");
        }

        return target;
    }
}
