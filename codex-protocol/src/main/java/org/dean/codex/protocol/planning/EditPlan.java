package org.dean.codex.protocol.planning;

import java.util.List;

public record EditPlan(String summary, List<PlannedEdit> edits) {

    public EditPlan {
        summary = summary == null ? "" : summary;
        edits = edits == null ? List.of() : List.copyOf(edits);
    }
}
