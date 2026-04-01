package org.dean.codex.protocol.appserver;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ThreadSourceKind {
    CLI,
    @JsonProperty("vscode")
    VS_CODE,
    EXEC,
    APP_SERVER,
    SUB_AGENT,
    SUB_AGENT_REVIEW,
    SUB_AGENT_COMPACT,
    SUB_AGENT_THREAD_SPAWN,
    SUB_AGENT_OTHER,
    UNKNOWN
}
