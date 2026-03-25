package org.dean.codex.protocol.conversation;

import java.time.Instant;

public record ConversationMessage(TurnId turnId,
                                  MessageRole role,
                                  String content,
                                  Instant createdAt) {
}
