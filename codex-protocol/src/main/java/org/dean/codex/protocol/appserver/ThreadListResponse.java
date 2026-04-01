package org.dean.codex.protocol.appserver;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.dean.codex.protocol.conversation.ThreadSummary;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ThreadListResponse(List<ThreadSummary> data, String nextCursor) {

    public ThreadListResponse {
        data = data == null ? List.of() : List.copyOf(data);
    }

    public ThreadListResponse(List<ThreadSummary> data) {
        this(data, null);
    }

    public List<ThreadSummary> threads() {
        return data;
    }
}
