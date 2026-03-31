package org.dean.codex.protocol.appserver;

public record ThreadCompactionStartedNotification(ThreadCompaction compaction) implements AppServerNotification {

    @Override
    public String method() {
        return "thread/compaction/started";
    }
}
