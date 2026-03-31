package org.dean.codex.protocol.appserver.jsonrpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcRequestMessage(String jsonrpc,
                                    JsonNode id,
                                    String method,
                                    JsonNode params) {

    public JsonRpcRequestMessage {
        jsonrpc = jsonrpc == null || jsonrpc.isBlank() ? "2.0" : jsonrpc;
    }

    @JsonIgnore
    public boolean isNotification() {
        return id == null || id.isNull();
    }
}
