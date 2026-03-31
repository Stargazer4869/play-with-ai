package org.dean.codex.protocol.appserver;

import org.dean.codex.protocol.item.TurnItem;
import org.dean.codex.protocol.runtime.RuntimeTurn;

public record TurnItemNotification(RuntimeTurn turn,
                                   TurnItem item) implements AppServerNotification {

    @Override
    public String method() {
        return "item/completed";
    }
}
