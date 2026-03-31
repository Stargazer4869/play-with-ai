package org.dean.codex.runtime.springai.appserver.transport.jsonrpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dean.codex.core.appserver.CodexAppServer;

public class JsonRpcAppServerDispatcher {

    private final CodexAppServer codexAppServer;
    private final ObjectMapper objectMapper;

    public JsonRpcAppServerDispatcher(CodexAppServer codexAppServer) {
        this(codexAppServer, new ObjectMapper().findAndRegisterModules());
    }

    public JsonRpcAppServerDispatcher(CodexAppServer codexAppServer, ObjectMapper objectMapper) {
        this.codexAppServer = codexAppServer;
        this.objectMapper = objectMapper;
    }

    public JsonRpcAppServerConnection open(JsonRpcMessageWriter writer) {
        return new JsonRpcAppServerConnection(codexAppServer.connect(), objectMapper, writer);
    }
}
