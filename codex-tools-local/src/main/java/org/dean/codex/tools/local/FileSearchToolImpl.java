package org.dean.codex.tools.local;

import org.dean.codex.core.tool.local.FileSearchTool;
import org.dean.codex.protocol.tool.FileSearchResult;
import org.dean.codex.protocol.tool.SearchMatch;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Component
public class FileSearchToolImpl implements FileSearchTool {

    private static final int MAX_RESULTS = 100;
    private static final Pattern RG_OUTPUT_PATTERN = Pattern.compile("^(.*?):(\\d+):(.*)$");

    private final Path workspaceRoot;

    public FileSearchToolImpl(@Qualifier("codexWorkspaceRoot") Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    @Override
    @Tool(description = "Search for text or regex matches inside files under the workspace root. Use this to locate classes, methods, config keys, and code snippets before reading or editing files.")
    public FileSearchResult search(String query, String scope) {
        if (query == null || query.isBlank()) {
            return new FileSearchResult(false, "", "", List.of(), 0, false, "Search query must not be blank.");
        }

        try {
            Path resolvedScope = resolveScope(scope);
            return searchWithRipgrep(query, resolvedScope, scope);
        }
        catch (IOException | InterruptedException exception) {
            try {
                Path resolvedScope = resolveScope(scope);
                return searchWithJava(query, resolvedScope, scope);
            }
            catch (Exception fallbackException) {
                return new FileSearchResult(false, query, normalizedScope(scope), List.of(), 0, false, fallbackException.getMessage());
            }
        }
        catch (Exception exception) {
            return new FileSearchResult(false, query, normalizedScope(scope), List.of(), 0, false, exception.getMessage());
        }
    }

    private FileSearchResult searchWithRipgrep(String query, Path resolvedScope, String requestedScope)
            throws IOException, InterruptedException {
        String scopeArgument = workspaceRoot.equals(resolvedScope)
                ? "."
                : workspaceRoot.relativize(resolvedScope).toString();

        Process process = new ProcessBuilder(
                "rg",
                "--line-number",
                "--with-filename",
                "--color",
                "never",
                "--",
                query,
                scopeArgument)
                .directory(workspaceRoot.toFile())
                .start();

        List<SearchMatch> matches = new ArrayList<>();
        int totalMatches = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                SearchMatch match = parseRipgrepLine(line);
                if (match != null) {
                    totalMatches++;
                    if (matches.size() < MAX_RESULTS) {
                        matches.add(match);
                    }
                }
            }
        }

        String stderr;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            stderr = reader.lines().reduce("", (left, right) -> left.isEmpty() ? right : left + System.lineSeparator() + right);
        }

        int exitCode = process.waitFor();
        if (exitCode == 0 || exitCode == 1) {
            return new FileSearchResult(
                    true,
                    query,
                    normalizedScope(requestedScope),
                    List.copyOf(matches),
                    totalMatches,
                    totalMatches > matches.size(),
                    "");
        }

        throw new IOException(stderr.isBlank() ? "ripgrep search failed." : stderr);
    }

    private FileSearchResult searchWithJava(String query, Path resolvedScope, String requestedScope) throws IOException {
        Pattern pattern;
        try {
            pattern = Pattern.compile(query);
        }
        catch (PatternSyntaxException exception) {
            return new FileSearchResult(false, query, normalizedScope(requestedScope), List.of(), 0, false, exception.getMessage());
        }

        List<SearchMatch> matches = new ArrayList<>();
        int totalMatches = 0;
        if (Files.isRegularFile(resolvedScope)) {
            totalMatches = searchFile(pattern, resolvedScope, matches, totalMatches);
        }
        else {
            try (var paths = Files.walk(resolvedScope)) {
                for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                    totalMatches = searchFile(pattern, path, matches, totalMatches);
                }
            }
        }

        return new FileSearchResult(
                true,
                query,
                normalizedScope(requestedScope),
                List.copyOf(matches),
                totalMatches,
                totalMatches > matches.size(),
                "");
    }

    private int searchFile(Pattern pattern, Path file, List<SearchMatch> matches, int totalMatches) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (pattern.matcher(line).find()) {
                    totalMatches++;
                    if (matches.size() < MAX_RESULTS) {
                        matches.add(new SearchMatch(relativePath(file), lineNumber, line));
                    }
                }
            }
            return totalMatches;
        }
        catch (MalformedInputException exception) {
            return totalMatches;
        }
    }

    private SearchMatch parseRipgrepLine(String line) {
        var matcher = RG_OUTPUT_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        return new SearchMatch(
                normalizeRelativePath(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                matcher.group(3));
    }

    private Path resolveScope(String scope) {
        if (scope == null || scope.isBlank() || ".".equals(scope.trim())) {
            return workspaceRoot;
        }

        Path resolved = workspaceRoot.resolve(scope).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Scope must remain within the workspace root.");
        }
        if (!Files.exists(resolved)) {
            throw new IllegalArgumentException("Scope does not exist: " + scope);
        }
        return resolved;
    }

    private String normalizedScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return ".";
        }
        return normalizeRelativePath(scope.trim());
    }

    private String relativePath(Path path) {
        return normalizeRelativePath(workspaceRoot.relativize(path).toString());
    }

    private String normalizeRelativePath(String value) {
        if (value == null || value.isBlank()) {
            return ".";
        }
        return value.startsWith("./") ? value.substring(2) : value;
    }
}
