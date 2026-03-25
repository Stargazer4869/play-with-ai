package org.dean.codex.protocol.tool;

public record SearchMatch(String path,
                          int lineNumber,
                          String lineContent) {
}
