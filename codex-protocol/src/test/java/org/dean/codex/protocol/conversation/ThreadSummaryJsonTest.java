package org.dean.codex.protocol.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dean.codex.protocol.agent.AgentStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadSummaryJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void roundTripsExtendedThreadMetadata() throws Exception {
        ThreadSummary summary = new ThreadSummary(
                new ThreadId("thread-1"),
                "Demo thread",
                Instant.parse("2026-04-01T00:00:00Z"),
                Instant.parse("2026-04-01T00:01:00Z"),
                2,
                "Inspect the transport layer changes",
                false,
                "openai",
                "gpt-5.4",
                ThreadStatus.ACTIVE,
                List.of(ThreadActiveFlag.WAITING_ON_APPROVAL),
                "/tmp/.codex-java/threads/thread-1",
                "/Users/chenzhu/Git/play-with-ai",
                ThreadSource.SUB_AGENT,
                true,
                Instant.parse("2026-04-01T00:02:00Z"),
                "worker-1",
                "worker",
                "root/worker-1",
                new ThreadId("thread-parent"),
                2,
                AgentStatus.RUNNING,
                Instant.parse("2026-04-01T00:03:00Z"));

        String json = objectMapper.writeValueAsString(summary);
        ThreadSummary restored = objectMapper.readValue(json, ThreadSummary.class);

        assertEquals(summary, restored);
        assertTrue(restored.loaded());
        assertTrue(restored.archived());
        assertEquals(new ThreadId("thread-parent"), restored.parentThreadId());
        assertEquals(Integer.valueOf(2), restored.agentDepth());
        assertEquals(AgentStatus.RUNNING, restored.agentStatus());
        assertEquals(Instant.parse("2026-04-01T00:03:00Z"), restored.agentClosedAt());
    }

    @Test
    void legacyConstructorAndPayloadStillWork() throws Exception {
        ThreadSummary legacyConstructed = new ThreadSummary(
                new ThreadId("thread-legacy"),
                "Legacy thread",
                Instant.parse("2026-04-01T00:00:00Z"),
                Instant.parse("2026-04-01T00:01:00Z"),
                1,
                "Legacy preview",
                false,
                "openai",
                "gpt-5.4",
                ThreadStatus.IDLE,
                List.of(),
                "/tmp/threads/thread-legacy",
                "/Users/chenzhu/Git/play-with-ai",
                ThreadSource.CLI,
                true,
                null,
                "worker-1",
                "worker",
                "root/worker-1");

        assertEquals(new ThreadId("thread-legacy"), legacyConstructed.threadId());
        assertNull(legacyConstructed.parentThreadId());
        assertNull(legacyConstructed.agentDepth());
        assertNull(legacyConstructed.agentStatus());
        assertNull(legacyConstructed.agentClosedAt());

        String legacyJson = """
                {
                  "threadId": { "value": "thread-legacy" },
                  "title": "Legacy thread",
                  "createdAt": "2026-04-01T00:00:00Z",
                  "updatedAt": "2026-04-01T00:01:00Z",
                  "turnCount": 1
                }
                """;

        ThreadSummary restored = objectMapper.readValue(legacyJson, ThreadSummary.class);

        assertEquals("Legacy thread", restored.preview());
        assertEquals(ThreadStatus.NOT_LOADED, restored.status());
        assertEquals(ThreadSource.UNKNOWN, restored.source());
        assertTrue(restored.materialized());
        assertFalse(restored.loaded());
        assertFalse(restored.archived());
        assertNull(restored.parentThreadId());
        assertNull(restored.agentDepth());
        assertNull(restored.agentStatus());
        assertNull(restored.agentClosedAt());
    }
}
