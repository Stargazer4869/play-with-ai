package org.dean.codex.core.agent;

import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.event.CodexTurnResult;

public interface TurnExecutor {

    CodexTurnResult executeTurn(ThreadId threadId, String input);

    CodexTurnResult resumeTurn(ThreadId threadId, TurnId turnId);
}
