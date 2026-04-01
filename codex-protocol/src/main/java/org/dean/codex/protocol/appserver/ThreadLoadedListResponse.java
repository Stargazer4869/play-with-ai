package org.dean.codex.protocol.appserver;

import org.dean.codex.protocol.conversation.ThreadId;

import java.util.List;

public record ThreadLoadedListResponse(List<ThreadId> data, String nextCursor) {

    public ThreadLoadedListResponse {
        data = data == null ? List.of() : List.copyOf(data);
    }
}
