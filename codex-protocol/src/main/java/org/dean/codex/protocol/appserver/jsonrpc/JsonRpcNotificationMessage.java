package org.dean.codex.protocol.appserver.jsonrpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcNotificationMessage(String jsonrpc,
                                         String method,
                                         JsonNode params) {

    public JsonRpcNotificationMessage {
        jsonrpc = jsonrpc == null || jsonrpc.isBlank() ? "2.0" : jsonrpc;
    }
}
