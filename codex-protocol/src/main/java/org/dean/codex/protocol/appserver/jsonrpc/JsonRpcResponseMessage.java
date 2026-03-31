package org.dean.codex.protocol.appserver.jsonrpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcResponseMessage(String jsonrpc,
                                     JsonNode id,
                                     JsonNode result,
                                     JsonRpcError error) {

    public JsonRpcResponseMessage {
        jsonrpc = jsonrpc == null || jsonrpc.isBlank() ? "2.0" : jsonrpc;
    }
}
