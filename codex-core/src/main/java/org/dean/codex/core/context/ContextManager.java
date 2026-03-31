package org.dean.codex.core.context;

import org.dean.codex.protocol.context.ThreadMemory;
import org.dean.codex.protocol.conversation.ThreadId;

import java.util.Optional;

public interface ContextManager {

    Optional<ThreadMemory> latestThreadMemory(ThreadId threadId);

    ThreadMemory compactThread(ThreadId threadId);
}
