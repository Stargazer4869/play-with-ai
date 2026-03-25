package org.dean.codex.protocol.tool;

public record FileWriteResult(boolean success,
                              String path,
                              boolean created,
                              int charactersWritten,
                              String error) {
}
