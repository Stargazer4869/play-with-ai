package org.dean.codex.runtime.springai.appserver.transport.jsonrpc;

import org.dean.codex.core.appserver.CodexAppServer;

import java.io.InputStream;
import java.io.OutputStream;

public class StdioJsonRpcAppServerLauncher {

    private final JsonRpcAppServerDispatcher dispatcher;

    public StdioJsonRpcAppServerLauncher(CodexAppServer appServer) {
        this(new JsonRpcAppServerDispatcher(appServer));
    }

    public StdioJsonRpcAppServerLauncher(JsonRpcAppServerDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public void run(InputStream inputStream, OutputStream outputStream) throws Exception {
        new StdioJsonRpcAppServerHost(dispatcher, inputStream, outputStream).run();
    }
}
