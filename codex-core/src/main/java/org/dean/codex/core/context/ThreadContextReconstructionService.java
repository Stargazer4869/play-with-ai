package org.dean.codex.core.context;

import org.dean.codex.protocol.context.ReconstructedThreadContext;
import org.dean.codex.protocol.conversation.ThreadId;

public interface ThreadContextReconstructionService {

    ReconstructedThreadContext reconstruct(ThreadId threadId);
}
