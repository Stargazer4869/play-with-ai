package org.dean.codex.protocol.conversation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.dean.codex.protocol.agent.AgentStatus;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ThreadSummary(ThreadId threadId,
                            String title,
                            Instant createdAt,
                            Instant updatedAt,
                            int turnCount,
                            String preview,
                            boolean ephemeral,
                            String modelProvider,
                            String model,
                            ThreadStatus status,
                            List<ThreadActiveFlag> activeFlags,
                            String path,
                            String cwd,
                            ThreadSource source,
                            Boolean materialized,
                            Instant archivedAt,
                            String agentNickname,
                            String agentRole,
                            String agentPath,
                            ThreadId parentThreadId,
                            Integer agentDepth,
                            AgentStatus agentStatus,
                            Instant agentClosedAt) {

    public ThreadSummary {
        title = title == null || title.isBlank() ? "New thread" : title.trim();
        preview = normalizePreview(preview, title);
        status = status == null ? ThreadStatus.NOT_LOADED : status;
        activeFlags = activeFlags == null ? List.of() : List.copyOf(activeFlags);
        source = source == null ? ThreadSource.UNKNOWN : source;
        materialized = materialized == null ? true : materialized;
    }

    public ThreadSummary(ThreadId threadId,
                         String title,
                         Instant createdAt,
                         Instant updatedAt,
                         int turnCount) {
        this(threadId,
                title,
                createdAt,
                updatedAt,
                turnCount,
                title,
                false,
                null,
                null,
                ThreadStatus.NOT_LOADED,
                List.of(),
                null,
                null,
                ThreadSource.UNKNOWN,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public ThreadSummary(ThreadId threadId,
                         String title,
                         Instant createdAt,
                         Instant updatedAt,
                         int turnCount,
                         String preview,
                         boolean ephemeral,
                         String modelProvider,
                         String model,
                         ThreadStatus status,
                         List<ThreadActiveFlag> activeFlags,
                         String path,
                         String cwd,
                         ThreadSource source,
                         Boolean materialized,
                         Instant archivedAt,
                         String agentNickname,
                         String agentRole,
                         String agentPath) {
        this(threadId,
                title,
                createdAt,
                updatedAt,
                turnCount,
                preview,
                ephemeral,
                modelProvider,
                model,
                status,
                activeFlags,
                path,
                cwd,
                source,
                materialized,
                archivedAt,
                agentNickname,
                agentRole,
                agentPath,
                null,
                null,
                null,
                null);
    }

    public boolean loaded() {
        return status != ThreadStatus.NOT_LOADED;
    }

    public boolean archived() {
        return archivedAt != null;
    }

    public ThreadSummary withRuntime(ThreadStatus runtimeStatus, List<ThreadActiveFlag> runtimeFlags) {
        return withRuntime(runtimeStatus, runtimeFlags, agentStatus);
    }

    public ThreadSummary withRuntime(ThreadStatus runtimeStatus, List<ThreadActiveFlag> runtimeFlags, AgentStatus runtimeAgentStatus) {
        return new ThreadSummary(
                threadId,
                title,
                createdAt,
                updatedAt,
                turnCount,
                preview,
                ephemeral,
                modelProvider,
                model,
                runtimeStatus,
                runtimeFlags,
                path,
                cwd,
                source,
                materialized,
                archivedAt,
                agentNickname,
                agentRole,
                agentPath,
                parentThreadId,
                agentDepth,
                runtimeAgentStatus == null ? agentStatus : runtimeAgentStatus,
                agentClosedAt);
    }

    private static String normalizePreview(String preview, String fallbackTitle) {
        String normalized = preview == null ? "" : preview.replaceAll("\\s+", " ").trim();
        if (!normalized.isEmpty()) {
            return normalized;
        }
        return fallbackTitle == null || fallbackTitle.isBlank() ? "New thread" : fallbackTitle.trim();
    }
}
