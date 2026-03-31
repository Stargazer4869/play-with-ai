package org.dean.codex.protocol.appserver;

import org.dean.codex.protocol.conversation.ThreadSummary;

import java.util.List;

public record ThreadListResponse(List<ThreadSummary> threads) {

    public ThreadListResponse {
        threads = threads == null ? List.of() : List.copyOf(threads);
    }
}
