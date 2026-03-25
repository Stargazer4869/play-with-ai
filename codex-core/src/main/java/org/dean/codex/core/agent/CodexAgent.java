package org.dean.codex.core.agent;

import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.event.CodexTurnResult;

public interface CodexAgent {

    CodexTurnResult handleTurn(ThreadId threadId, TurnId turnId, String input);
}
