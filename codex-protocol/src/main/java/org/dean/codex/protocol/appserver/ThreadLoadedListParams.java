package org.dean.codex.protocol.appserver;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ThreadLoadedListParams(String cursor, Integer limit) {

    public ThreadLoadedListParams() {
        this(null, null);
    }
}
