package org.dean.codex.protocol.appserver;

import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;

public record TurnResumeParams(ThreadId threadId,
                               TurnId turnId) {
}
