package org.dean.codex.protocol.appserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadCompactionJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void roundTripsCompactionMetadataAndNotifications() throws Exception {
        ThreadCompaction compaction = new ThreadCompaction(
                "comp-1",
                new ThreadId("thread-1"),
                List.of(new TurnId("turn-1"), new TurnId("turn-2")),
                2,
                "Compacted earlier thread context.",
                Instant.parse("2026-03-31T00:00:00Z"),
                Instant.parse("2026-03-31T00:01:00Z"));

        String metadataJson = objectMapper.writeValueAsString(compaction);
        ThreadCompaction restoredMetadata = objectMapper.readValue(metadataJson, ThreadCompaction.class);
        assertEquals(compaction, restoredMetadata);
        assertTrue(restoredMetadata.completed());

        String startedJson = objectMapper.writeValueAsString(new ThreadCompactionStartedNotification(compaction));
        ThreadCompactionStartedNotification restoredStarted = objectMapper.readValue(
                startedJson,
                ThreadCompactionStartedNotification.class);
        assertEquals(compaction, restoredStarted.compaction());

        String completedJson = objectMapper.writeValueAsString(new ThreadCompactedNotification(compaction));
        ThreadCompactedNotification restoredCompleted = objectMapper.readValue(
                completedJson,
                ThreadCompactedNotification.class);
        assertEquals(compaction, restoredCompleted.compaction());
        assertInstanceOf(ThreadCompaction.class, restoredCompleted.compaction());
    }
}
