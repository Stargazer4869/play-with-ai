package org.dean.codex.protocol.tool;

public record FilePatchResult(boolean success,
                              String path,
                              int replacements,
                              int charactersDelta,
                              String error) {
}
