package org.dean.codex.protocol.appserver;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ThreadSortKey {
    @JsonProperty("createdAt")
    CREATED_AT,
    @JsonProperty("updatedAt")
    UPDATED_AT
}
