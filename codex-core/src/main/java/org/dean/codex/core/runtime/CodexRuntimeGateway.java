package org.dean.codex.core.runtime;

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
