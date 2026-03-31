package org.dean.codex.protocol.appserver;

public sealed interface AppServerNotification permits ThreadStartedNotification,
        ThreadCompactionStartedNotification,
        ThreadCompactedNotification,
        TurnStartedNotification,
        TurnCompletedNotification,
        TurnItemNotification {

    String method();
}
