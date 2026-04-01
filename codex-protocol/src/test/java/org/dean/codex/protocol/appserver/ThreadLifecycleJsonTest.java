package org.dean.codex.protocol.appserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dean.codex.protocol.context.ReconstructedThreadContext;
import org.dean.codex.protocol.context.ThreadMemory;
import org.dean.codex.protocol.conversation.ThreadSummary;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.ThreadSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ThreadLifecycleJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void roundTripsThreadLifecycleRequestsAndResponses() throws Exception {
        ThreadListParams listParams = new ThreadListParams(
                "cursor-1",
                25,
                ThreadSortKey.UPDATED_AT,
                List.of("openai"),
                List.of(ThreadSourceKind.CLI, ThreadSourceKind.SUB_AGENT),
                Boolean.TRUE,
                "/Users/chenzhu/Git/play-with-ai",
                "thread");
        assertEquals(listParams, objectMapper.readValue(objectMapper.writeValueAsString(listParams), ThreadListParams.class));

        ThreadReadParams readParams = new ThreadReadParams(new ThreadId("thread-1"), true);
        assertEquals(readParams, objectMapper.readValue(objectMapper.writeValueAsString(readParams), ThreadReadParams.class));

        ThreadForkParams forkParams = new ThreadForkParams(
                new ThreadId("thread-1"),
                "Forked thread",
                Boolean.FALSE,
                "/tmp/worktree",
                "openai",
                "gpt-5.4",
                ThreadSource.APP_SERVER,
                "worker-1",
                "worker",
                "root/worker-1");
        assertEquals(forkParams, objectMapper.readValue(objectMapper.writeValueAsString(forkParams), ThreadForkParams.class));

        ThreadArchiveParams archiveParams = new ThreadArchiveParams(new ThreadId("thread-1"));
        assertEquals(archiveParams, objectMapper.readValue(objectMapper.writeValueAsString(archiveParams), ThreadArchiveParams.class));

        ThreadUnarchiveParams unarchiveParams = new ThreadUnarchiveParams(new ThreadId("thread-1"));
        assertEquals(unarchiveParams, objectMapper.readValue(objectMapper.writeValueAsString(unarchiveParams), ThreadUnarchiveParams.class));

        ThreadRollbackParams rollbackParams = new ThreadRollbackParams(new ThreadId("thread-1"), 2);
        assertEquals(rollbackParams, objectMapper.readValue(objectMapper.writeValueAsString(rollbackParams), ThreadRollbackParams.class));

        ThreadLoadedListParams loadedListParams = new ThreadLoadedListParams("cursor-2", 50);
        assertEquals(loadedListParams, objectMapper.readValue(objectMapper.writeValueAsString(loadedListParams), ThreadLoadedListParams.class));

        ThreadLoadedListResponse loadedListResponse = new ThreadLoadedListResponse(List.of(new ThreadId("thread-1")), "next-cursor");
        assertEquals(loadedListResponse, objectMapper.readValue(objectMapper.writeValueAsString(loadedListResponse), ThreadLoadedListResponse.class));

        ThreadListResponse listResponse = new ThreadListResponse(
                List.of(new ThreadSummary(
                        new ThreadId("thread-1"),
                        "Demo thread",
                        Instant.parse("2026-04-01T00:00:00Z"),
                        Instant.parse("2026-04-01T00:00:05Z"),
                        2)),
                "cursor-3");
        assertEquals(listResponse, objectMapper.readValue(objectMapper.writeValueAsString(listResponse), ThreadListResponse.class));

        ThreadReadResponse readResponse = new ThreadReadResponse(
                new ThreadSummary(
                        new ThreadId("thread-1"),
                        "Demo thread",
                        Instant.parse("2026-04-01T00:00:00Z"),
                        Instant.parse("2026-04-01T00:00:05Z"),
                        2),
                List.of(),
                new ThreadMemory("memory-1", new ThreadId("thread-1"), "summary", List.of(), 0, Instant.parse("2026-04-01T00:00:06Z")),
                new ReconstructedThreadContext(new ThreadId("thread-1"), null, List.of(), List.of(), List.of(), Instant.parse("2026-04-01T00:00:07Z")),
                new ThreadId("thread-root"),
                List.of(new ThreadSummary(
                        new ThreadId("thread-child"),
                        "Child thread",
                        Instant.parse("2026-04-01T00:00:01Z"),
                        Instant.parse("2026-04-01T00:00:08Z"),
                        1)));
        assertEquals(readResponse, objectMapper.readValue(objectMapper.writeValueAsString(readResponse), ThreadReadResponse.class));
    }

    @Test
    void deserializesLegacyReadParamsWithDefaultIncludeTurns() throws Exception {
        ThreadReadParams restored = objectMapper.readValue("""
                {
                  "threadId": { "value": "thread-legacy" }
                }
                """, ThreadReadParams.class);

        assertEquals(new ThreadId("thread-legacy"), restored.threadId());
        assertFalse(restored.includeTurns());
    }
}
