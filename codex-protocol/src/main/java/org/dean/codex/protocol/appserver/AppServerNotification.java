package org.dean.codex.protocol.appserver;

public sealed interface AppServerNotification permits ThreadStartedNotification,
        ThreadCompactedNotification,
        TurnStartedNotification,
        TurnCompletedNotification,
        TurnItemNotification {

    String method();
}
