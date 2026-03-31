package org.dean.codex.protocol.appserver.jsonrpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcError(int code,
                           String message,
                           JsonNode data) {
}
