package org.dean.codex.tools.local;

import org.dean.codex.core.tool.local.FilePatchTool;
import org.dean.codex.protocol.tool.FilePatchResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class FilePatchToolImpl implements FilePatchTool {

    private final Path workspaceRoot;

    public FilePatchToolImpl(@Qualifier("codexWorkspaceRoot") Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    @Override
    @Tool(description = "Apply a precise text patch inside an existing file by replacing exact old text with new text. Use this for targeted edits instead of rewriting a full file.")
    public FilePatchResult applyPatch(String path, String oldText, String newText, boolean replaceAll) {
        if (path == null || path.isBlank()) {
            return new FilePatchResult(false, "", 0, 0, "Patch path must not be blank.");
        }
        if (oldText == null || oldText.isEmpty()) {
            return new FilePatchResult(false, path, 0, 0, "Patch oldText must not be blank.");
        }

        try {
            Path resolvedPath = resolvePath(path);
            if (!Files.exists(resolvedPath) || !Files.isRegularFile(resolvedPath)) {
                return new FilePatchResult(false, path, 0, 0, "Patch target does not exist: " + path);
            }

            String currentContent = Files.readString(resolvedPath, StandardCharsets.UTF_8);
            int occurrences = countOccurrences(currentContent, oldText);
            if (occurrences == 0) {
                return new FilePatchResult(false, path, 0, 0, "Patch oldText was not found in the target file.");
            }
            if (occurrences > 1 && !replaceAll) {
                return new FilePatchResult(false, path, occurrences, 0,
                        "Patch oldText matched multiple locations. Use a more specific snippet or set replaceAll=true.");
            }

            String replacement = newText == null ? "" : newText;
            String updatedContent = replaceAll
                    ? currentContent.replace(oldText, replacement)
                    : currentContent.replaceFirst(java.util.regex.Pattern.quote(oldText),
                    java.util.regex.Matcher.quoteReplacement(replacement));
            Files.writeString(resolvedPath, updatedContent, StandardCharsets.UTF_8);
            return new FilePatchResult(
                    true,
                    path,
                    replaceAll ? occurrences : 1,
                    updatedContent.length() - currentContent.length(),
                    "");
        }
        catch (Exception exception) {
            return new FilePatchResult(false, path, 0, 0, exception.getMessage());
        }
    }

    private Path resolvePath(String path) {
        Path resolved = workspaceRoot.resolve(path).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Path must remain within the workspace root.");
        }
        return resolved;
    }

    private int countOccurrences(String content, String snippet) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(snippet, index)) >= 0) {
            count++;
            index += snippet.length();
        }
        return count;
    }
}
