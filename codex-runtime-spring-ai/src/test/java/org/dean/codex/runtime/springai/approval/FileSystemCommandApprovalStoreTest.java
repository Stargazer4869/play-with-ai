package org.dean.codex.runtime.springai.approval;

import org.dean.codex.protocol.approval.ApprovalId;
import org.dean.codex.protocol.approval.ApprovalStatus;
import org.dean.codex.protocol.approval.CommandApprovalRequest;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemCommandApprovalStoreTest {

    @TempDir
    Path storageRoot;

    @Test
    void savesAndReloadsApprovalRequests() {
        FileSystemCommandApprovalStore store = new FileSystemCommandApprovalStore(storageRoot);
        ThreadId threadId = new ThreadId("thread-1");
        CommandApprovalRequest request = new CommandApprovalRequest(
                new ApprovalId("approval-1"),
                threadId,
                new TurnId("turn-1"),
                "mvn test",
                "/tmp/workspace",
                "Needs approval",
                ApprovalStatus.PENDING,
                Instant.now(),
                Instant.now(),
                "",
                null);

        store.save(request);

        assertTrue(store.find(new ApprovalId("approval-1")).isPresent());
        assertEquals(1, store.list(threadId).size());
        assertEquals("mvn test", store.list(threadId).get(0).command());
    }
}
