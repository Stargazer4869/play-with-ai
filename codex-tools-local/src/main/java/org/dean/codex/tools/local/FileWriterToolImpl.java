package org.dean.codex.tools.local;

import org.dean.codex.core.tool.local.FileWriterTool;
import org.dean.codex.protocol.tool.FileWriteResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Component
public class FileWriterToolImpl implements FileWriterTool {

    private static final Logger logger = LoggerFactory.getLogger(FileWriterToolImpl.class);

    private final Path workspaceRoot;

    public FileWriterToolImpl(@Qualifier("codexWorkspaceRoot") Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    @Override
    @Tool(description = "Create or overwrite a UTF-8 text file relative to the project root. Parent directories are created automatically. The path must stay inside the project root.")
    public FileWriteResult writeFile(String path, String content) {
        logger.info("Copilot tool writeFile used with path={}, contentLength={}", summarizePath(path), content == null ? "<null>" : content.length());
        try {
            Path target = resolvePath(path);
            if (Files.exists(target) && Files.isDirectory(target)) {
                return new FileWriteResult(false, workspaceRoot.relativize(target).toString(), false, 0, "Path points to a directory, not a file.");
            }

            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            boolean created = Files.notExists(target);
            String safeContent = content == null ? "" : content;
            Files.writeString(target, safeContent, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return new FileWriteResult(true, workspaceRoot.relativize(target).toString(), created, safeContent.length(), "");
        }
        catch (Exception exception) {
            return new FileWriteResult(false, path, false, 0, exception.getMessage());
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
