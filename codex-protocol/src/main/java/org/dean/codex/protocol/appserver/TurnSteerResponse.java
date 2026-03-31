package org.dean.codex.protocol.appserver;

import org.dean.codex.protocol.conversation.TurnId;

public record TurnSteerResponse(TurnId turnId,
                                boolean accepted) {
}
