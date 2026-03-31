package org.dean.codex.core.runtime;

import org.dean.codex.protocol.context.ReconstructedThreadContext;
import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.ThreadSummary;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.event.CodexTurnResult;
import org.dean.codex.protocol.item.TurnItem;

import java.util.List;
import java.util.function.Consumer;

public interface CodexRuntime {

    ThreadId startThread(String title);

    List<ThreadSummary> listThreads();

    List<ConversationTurn> turns(ThreadId threadId);

    ConversationTurn turn(ThreadId threadId, TurnId turnId);

    ReconstructedThreadContext reconstructThreadContext(ThreadId threadId);

    CodexTurnResult startTurn(ThreadId threadId, String input, Consumer<TurnItem> itemConsumer);

    CodexTurnResult resumeTurn(ThreadId threadId, TurnId turnId, Consumer<TurnItem> itemConsumer);
}
