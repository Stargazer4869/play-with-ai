package org.dean.codex.core.agent;

import java.util.List;

public interface TurnControl {

    default boolean interruptionRequested() {
        return false;
    }

    default List<String> drainSteeringInputs() {
        return List.of();
    }
}
