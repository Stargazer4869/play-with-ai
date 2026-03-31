package org.dean.codex.protocol.appserver;

import org.dean.codex.protocol.runtime.RuntimeTurn;

public record TurnCompletedNotification(RuntimeTurn turn,
                                        String finalAnswer) implements AppServerNotification {

    @Override
    public String method() {
        return "turn/completed";
    }
}
