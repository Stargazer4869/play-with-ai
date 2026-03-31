package org.dean.codex.runtime.springai.appserver.transport.jsonrpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.dean.codex.protocol.appserver.jsonrpc.JsonRpcError;
import org.dean.codex.protocol.appserver.jsonrpc.JsonRpcNotificationMessage;
import org.dean.codex.protocol.appserver.jsonrpc.JsonRpcRequestMessage;
import org.dean.codex.protocol.appserver.jsonrpc.JsonRpcResponseMessage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public class StdioJsonRpcAppServerHost {

    private final JsonRpcAppServerDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private final BufferedReader reader;
    private final BufferedWriter writer;

    public StdioJsonRpcAppServerHost(JsonRpcAppServerDispatcher dispatcher,
                                     InputStream inputStream,
                                     OutputStream outputStream) {
        this(dispatcher,
                new ObjectMapper().findAndRegisterModules(),
                new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8)),
                new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)));
    }

    StdioJsonRpcAppServerHost(JsonRpcAppServerDispatcher dispatcher,
                              ObjectMapper objectMapper,
                              BufferedReader reader,
                              BufferedWriter writer) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
        this.reader = reader;
        this.writer = writer;
    }

    public void run() throws Exception {
        try (JsonRpcAppServerConnection connection = dispatcher.open(new StdioMessageWriter())) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonRpcRequestMessage request = objectMapper.readValue(line, JsonRpcRequestMessage.class);
                    connection.accept(request);
                }
                catch (JsonProcessingException exception) {
                    writeLine(new JsonRpcResponseMessage(
                            "2.0",
                            NullNode.getInstance(),
                            null,
                            new JsonRpcError(-32700, "Parse error", null)));
                }
            }
            writer.flush();
        }
    }

    private synchronized void writeLine(Object message) throws IOException {
        writer.write(objectMapper.writeValueAsString(message));
        writer.newLine();
        writer.flush();
    }

    private final class StdioMessageWriter implements JsonRpcMessageWriter {

        @Override
        public void writeResponse(JsonRpcResponseMessage response) throws IOException {
            writeLine(response);
        }

        @Override
        public void writeNotification(JsonRpcNotificationMessage notification) throws IOException {
            writeLine(notification);
        }
    }
}
