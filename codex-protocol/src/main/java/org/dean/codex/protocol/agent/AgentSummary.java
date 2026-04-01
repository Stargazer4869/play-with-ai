package org.dean.codex.protocol.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.dean.codex.protocol.conversation.ThreadId;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentSummary(ThreadId threadId,
                           ThreadId parentThreadId,
                           String nickname,
                           String role,
                           String path,
                           Integer depth,
                           AgentStatus status,
                           Instant createdAt,
                           Instant updatedAt,
                           Instant closedAt) {

    public AgentSummary {
        nickname = normalize(nickname);
        role = normalize(role);
        path = normalize(path);
    }

    public boolean closed() {
        return closedAt != null;
    }

    private static String normalize(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
