package org.dean.codex.protocol.appserver;

import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;

public record TurnSteerParams(ThreadId threadId,
                              TurnId turnId,
                              String input) {
}
