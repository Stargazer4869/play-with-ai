package org.dean.codex.protocol.tool;

public record FileReadResult(boolean success,
                             String path,
                             String content,
                             boolean truncated,
                             int totalCharacters,
                             String error) {
}
