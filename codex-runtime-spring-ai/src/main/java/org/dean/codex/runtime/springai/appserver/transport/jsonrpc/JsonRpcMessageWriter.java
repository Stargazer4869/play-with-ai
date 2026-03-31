package org.dean.codex.runtime.springai.appserver.transport.jsonrpc;

import org.dean.codex.protocol.appserver.jsonrpc.JsonRpcNotificationMessage;
import org.dean.codex.protocol.appserver.jsonrpc.JsonRpcResponseMessage;

import java.io.IOException;

public interface JsonRpcMessageWriter {

    void writeResponse(JsonRpcResponseMessage response) throws IOException;

    void writeNotification(JsonRpcNotificationMessage notification) throws IOException;
}
