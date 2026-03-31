package org.dean.codex.protocol.planning;

public record PlannedEdit(String path,
                          PlannedEditType type,
                          String description) {
}
