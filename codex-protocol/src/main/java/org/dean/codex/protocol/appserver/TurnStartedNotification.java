package org.dean.codex.protocol.appserver;

import org.dean.codex.protocol.runtime.RuntimeTurn;

public record TurnStartedNotification(RuntimeTurn turn) implements AppServerNotification {

    @Override
    public String method() {
        return "turn/started";
    }
}
