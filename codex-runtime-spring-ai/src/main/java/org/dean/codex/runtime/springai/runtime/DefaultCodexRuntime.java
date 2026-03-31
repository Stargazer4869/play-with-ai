package org.dean.codex.runtime.springai.runtime;

import org.dean.codex.core.agent.TurnExecutor;
import org.dean.codex.core.context.ThreadContextReconstructionService;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.core.runtime.CodexRuntime;
import org.dean.codex.protocol.context.ReconstructedThreadContext;
import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.ThreadSummary;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.event.CodexTurnResult;
import org.dean.codex.protocol.item.TurnItem;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

@Component
public class DefaultCodexRuntime implements CodexRuntime {

    private final ConversationStore conversationStore;
    private final TurnExecutor turnExecutor;
    private final ThreadContextReconstructionService threadContextReconstructionService;

    public DefaultCodexRuntime(ConversationStore conversationStore,
                               TurnExecutor turnExecutor,
                               ThreadContextReconstructionService threadContextReconstructionService) {
        this.conversationStore = conversationStore;
        this.turnExecutor = turnExecutor;
        this.threadContextReconstructionService = threadContextReconstructionService;
    }

    @Override
    public ThreadId startThread(String title) {
        return conversationStore.createThread(title);
    }

    @Override
    public List<ThreadSummary> listThreads() {
        return conversationStore.listThreads();
    }

    @Override
    public List<ConversationTurn> turns(ThreadId threadId) {
        return conversationStore.turns(threadId);
    }

    @Override
    public ConversationTurn turn(ThreadId threadId, TurnId turnId) {
        return conversationStore.turn(threadId, turnId);
    }

    @Override
    public ReconstructedThreadContext reconstructThreadContext(ThreadId threadId) {
        return threadContextReconstructionService.reconstruct(threadId);
    }

    @Override
    public CodexTurnResult startTurn(ThreadId threadId, String input, Consumer<TurnItem> itemConsumer) {
        return turnExecutor.executeTurn(threadId, input, itemConsumer);
    }

    @Override
    public CodexTurnResult resumeTurn(ThreadId threadId, TurnId turnId, Consumer<TurnItem> itemConsumer) {
        return turnExecutor.resumeTurn(threadId, turnId, itemConsumer);
    }
}
