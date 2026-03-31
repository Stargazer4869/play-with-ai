package org.dean.codex.protocol.appserver;

public record ThreadCompactedNotification(ThreadCompaction compaction) implements AppServerNotification {

    @Override
    public String method() {
        return "thread/compacted";
    }
}
