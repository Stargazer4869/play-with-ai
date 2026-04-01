package org.dean.codex.protocol.appserver;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.dean.codex.protocol.context.ReconstructedThreadContext;
import org.dean.codex.protocol.context.ThreadMemory;
import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.ThreadSummary;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ThreadReadResponse(ThreadSummary thread,
                                 List<ConversationTurn> turns,
                                 ThreadMemory threadMemory,
                                 ReconstructedThreadContext reconstructedContext,
                                 ThreadId treeRootThreadId,
                                 List<ThreadSummary> relatedThreads) {

    public ThreadReadResponse(ThreadSummary thread,
                              List<ConversationTurn> turns,
                              ThreadMemory threadMemory,
                              ReconstructedThreadContext reconstructedContext) {
        this(thread, turns, threadMemory, reconstructedContext, thread == null ? null : thread.threadId(), List.of());
    }

    public ThreadReadResponse {
        turns = turns == null ? List.of() : List.copyOf(turns);
        relatedThreads = relatedThreads == null ? List.of() : List.copyOf(relatedThreads);
    }
}
