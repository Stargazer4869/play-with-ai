package org.dean.codex.protocol.appserver;

import org.dean.codex.protocol.conversation.TurnId;

public record TurnInterruptResponse(TurnId turnId,
                                    boolean accepted) {
}
