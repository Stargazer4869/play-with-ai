package org.dean.codex.protocol.appserver;

import org.dean.codex.protocol.context.ThreadMemory;

public record ThreadCompactStartResponse(ThreadCompaction compaction,
                                         ThreadMemory threadMemory) {
}
