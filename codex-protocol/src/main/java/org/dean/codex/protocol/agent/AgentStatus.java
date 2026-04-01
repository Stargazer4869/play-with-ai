package org.dean.codex.protocol.agent;

public enum AgentStatus {
    PENDING_INIT,
    IDLE,
    RUNNING,
    WAITING,
    INTERRUPTED,
    COMPLETED,
    ERRORED,
    SHUTDOWN,
    NOT_FOUND
}
