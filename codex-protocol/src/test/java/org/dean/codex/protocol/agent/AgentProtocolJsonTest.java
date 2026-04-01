package org.dean.codex.protocol.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentProtocolJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void roundTripsAgentLifecycleModels() throws Exception {
        AgentSummary summary = new AgentSummary(
                new ThreadId("thread-agent"),
                new ThreadId("thread-parent"),
                "worker-1",
                "reviewer",
                "root/worker-1",
                2,
                AgentStatus.RUNNING,
                Instant.parse("2026-04-01T00:00:00Z"),
                Instant.parse("2026-04-01T00:01:00Z"),
                Instant.parse("2026-04-01T00:02:00Z"));
        AgentSpawnRequest spawnRequest = new AgentSpawnRequest(
                new ThreadId("thread-parent"),
                "Review the change",
                "Inspect the thread tree",
                "worker-1",
                "reviewer",
                2,
                "openai",
                "gpt-5.4",
                "/workspace");
        AgentMessage message = new AgentMessage(
                new ThreadId("thread-parent"),
                new ThreadId("thread-agent"),
                "Please review the fork",
                Instant.parse("2026-04-01T00:03:00Z"));
        AgentWaitResult waitResult = new AgentWaitResult(
                new ThreadId("thread-agent"),
                new TurnId("turn-agent-1"),
                AgentStatus.RUNNING,
                AgentStatus.IDLE,
                false,
                "Completed successfully",
                "Review complete",
                Instant.parse("2026-04-01T00:04:00Z"));

        assertEquals(summary, objectMapper.readValue(objectMapper.writeValueAsString(summary), AgentSummary.class));
        assertEquals(spawnRequest, objectMapper.readValue(objectMapper.writeValueAsString(spawnRequest), AgentSpawnRequest.class));
        assertEquals(message, objectMapper.readValue(objectMapper.writeValueAsString(message), AgentMessage.class));
        assertEquals(waitResult, objectMapper.readValue(objectMapper.writeValueAsString(waitResult), AgentWaitResult.class));
        assertTrue(summary.closed());
        assertFalse(waitResult.timedOut());
    }
}
