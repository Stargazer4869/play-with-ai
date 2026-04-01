package org.dean.codex.core.runtime;

import org.dean.codex.protocol.appserver.ThreadForkParams;
import org.dean.codex.protocol.context.ReconstructedThreadContext;
import org.dean.codex.protocol.context.ThreadMemory;
import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.ThreadSummary;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.runtime.RuntimeNotification;
import org.dean.codex.protocol.runtime.RuntimeTurn;
import org.dean.codex.protocol.skill.SkillMetadata;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public interface CodexRuntimeGateway {

    ThreadSummary threadStart(String title);

    ThreadSummary threadResume(ThreadId threadId);

    List<ThreadSummary> listThreads();

    default List<ThreadId> loadedThreads() {
        return listThreads().stream()
                .filter(ThreadSummary::loaded)
                .map(ThreadSummary::threadId)
                .toList();
    }

    default ThreadId threadTreeRoot(ThreadId threadId) {
        return threadId;
    }

    default List<ThreadSummary> relatedThreads(ThreadId threadId) {
        return List.of();
    }

    default ThreadSummary threadFork(ThreadForkParams params) {
        throw new UnsupportedOperationException("thread/fork is not implemented yet");
    }

    default ThreadSummary threadUnarchive(ThreadId threadId) {
        throw new UnsupportedOperationException("thread/unarchive is not implemented yet");
    }

    default ThreadSummary threadRollback(ThreadId threadId, int numTurns) {
        throw new UnsupportedOperationException("thread/rollback is not implemented yet");
    }

    default ThreadSummary threadArchive(ThreadId threadId) {
        throw new UnsupportedOperationException("thread/archive is not implemented yet");
    }

    List<ConversationTurn> turns(ThreadId threadId);

    ConversationTurn turn(ThreadId threadId, TurnId turnId);

    ReconstructedThreadContext reconstructThreadContext(ThreadId threadId);

    Optional<ThreadMemory> latestThreadMemory(ThreadId threadId);

    ThreadMemory compactThread(ThreadId threadId);

    List<SkillMetadata> listSkills(boolean forceReload);

    RuntimeTurn turnStart(ThreadId threadId, String input);

    RuntimeTurn turnResume(ThreadId threadId, TurnId turnId);

    boolean turnSteer(ThreadId threadId, TurnId turnId, String input);

    boolean turnInterrupt(ThreadId threadId, TurnId turnId);

    AutoCloseable subscribe(ThreadId threadId, Consumer<RuntimeNotification> listener);
}
