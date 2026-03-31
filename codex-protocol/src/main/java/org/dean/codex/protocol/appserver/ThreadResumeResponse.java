package org.dean.codex.protocol.appserver;

import org.dean.codex.protocol.conversation.ThreadSummary;

public record ThreadResumeResponse(ThreadSummary thread) {
}
