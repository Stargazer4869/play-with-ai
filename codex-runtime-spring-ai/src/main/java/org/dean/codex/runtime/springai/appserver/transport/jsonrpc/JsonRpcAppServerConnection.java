package org.dean.codex.runtime.springai.appserver.transport.jsonrpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dean.codex.core.appserver.CodexAppServerSession;
import org.dean.codex.protocol.appserver.AppServerNotification;
import org.dean.codex.protocol.appserver.InitializeParams;
import org.dean.codex.protocol.appserver.InitializedNotification;
import org.dean.codex.protocol.appserver.SkillsListParams;
import org.dean.codex.protocol.appserver.ThreadArchiveParams;
import org.dean.codex.protocol.appserver.ThreadCompactStartParams;
import org.dean.codex.protocol.appserver.ThreadForkParams;
import org.dean.codex.protocol.appserver.ThreadListParams;
import org.dean.codex.protocol.appserver.ThreadListResponse;
import org.dean.codex.protocol.appserver.ThreadLoadedListParams;
import org.dean.codex.protocol.appserver.ThreadReadParams;
import org.dean.codex.protocol.appserver.ThreadRollbackParams;
import org.dean.codex.protocol.appserver.ThreadResumeParams;
import org.dean.codex.protocol.appserver.ThreadStartParams;
import org.dean.codex.protocol.appserver.ThreadUnarchiveParams;
import org.dean.codex.protocol.appserver.TurnInterruptParams;
import org.dean.codex.protocol.appserver.TurnResumeParams;
import org.dean.codex.protocol.appserver.TurnStartParams;
import org.dean.codex.protocol.appserver.TurnSteerParams;
import org.dean.codex.protocol.appserver.jsonrpc.JsonRpcError;
import org.dean.codex.protocol.appserver.jsonrpc.JsonRpcNotificationMessage;
import org.dean.codex.protocol.appserver.jsonrpc.JsonRpcRequestMessage;
import org.dean.codex.protocol.appserver.jsonrpc.JsonRpcResponseMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class JsonRpcAppServerConnection implements AutoCloseable {

    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;
    private static final int SERVER_ERROR = -32000;

    private final CodexAppServerSession session;
    private final ObjectMapper objectMapper;
    private final JsonRpcMessageWriter writer;
    private final List<JsonRpcNotificationMessage> pendingNotifications = new ArrayList<>();
    private AutoCloseable subscription;
    private boolean dispatchingRequest;

    public JsonRpcAppServerConnection(CodexAppServerSession session,
                                      ObjectMapper objectMapper,
                                      JsonRpcMessageWriter writer) {
        this.session = session;
        this.objectMapper = objectMapper;
        this.writer = writer;
    }

    public synchronized void accept(JsonRpcRequestMessage message) throws IOException {
        if (message == null || message.method() == null || message.method().isBlank()) {
            writeError(null, INVALID_REQUEST, "Invalid request");
            return;
        }

        JsonNode requestId = message.id();
        try {
            dispatchingRequest = true;
            Object result = dispatch(message);
            if (!message.isNotification()) {
                writer.writeResponse(new JsonRpcResponseMessage(
                        "2.0",
                        requestId,
                        result == null ? null : objectMapper.valueToTree(result),
                        null));
            }
        }
        catch (NoSuchMethodException exception) {
            if (!message.isNotification()) {
                writeError(requestId, METHOD_NOT_FOUND, exception.getMessage());
            }
        }
        catch (IllegalArgumentException exception) {
            if (!message.isNotification()) {
                writeError(requestId, INVALID_PARAMS, exception.getMessage());
            }
        }
        catch (UnsupportedOperationException exception) {
            if (!message.isNotification()) {
                writeError(requestId, SERVER_ERROR, exception.getMessage());
            }
        }
        catch (IllegalStateException exception) {
            if (!message.isNotification()) {
                writeError(requestId, SERVER_ERROR, exception.getMessage());
            }
        }
        catch (Exception exception) {
            if (!message.isNotification()) {
                writeError(requestId, INTERNAL_ERROR, exception.getMessage());
            }
        }
        finally {
            dispatchingRequest = false;
            flushPendingNotifications();
        }
    }

    @Override
    public synchronized void close() throws Exception {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        session.close();
    }

    private Object dispatch(JsonRpcRequestMessage message) throws Exception {
        return switch (message.method()) {
            case "initialize" -> session.initialize(readParams(message.params(), InitializeParams.class));
            case "initialized" -> {
                session.initialized(readParams(message.params(), InitializedNotification.class));
                ensureNotificationSubscription();
                yield null;
            }
            case "thread/start" -> session.threadStart(readParams(message.params(), ThreadStartParams.class));
            case "thread/resume" -> session.threadResume(readParams(message.params(), ThreadResumeParams.class));
            case "thread/list" -> session.threadList(readParams(message.params(), ThreadListParams.class));
            case "thread/loaded/list" -> session.threadLoadedList(readParams(message.params(), ThreadLoadedListParams.class));
            case "thread/read" -> session.threadRead(readParams(message.params(), ThreadReadParams.class));
            case "thread/fork" -> session.threadFork(readParams(message.params(), ThreadForkParams.class));
            case "thread/archive" -> session.threadArchive(readParams(message.params(), ThreadArchiveParams.class));
            case "thread/unarchive" -> session.threadUnarchive(readParams(message.params(), ThreadUnarchiveParams.class));
            case "thread/rollback" -> session.threadRollback(readParams(message.params(), ThreadRollbackParams.class));
            case "thread/compact/start" -> session.threadCompactStart(readParams(message.params(), ThreadCompactStartParams.class));
            case "turn/start" -> session.turnStart(readParams(message.params(), TurnStartParams.class));
            case "turn/resume" -> session.turnResume(readParams(message.params(), TurnResumeParams.class));
            case "turn/interrupt" -> session.turnInterrupt(readParams(message.params(), TurnInterruptParams.class));
            case "turn/steer" -> session.turnSteer(readParams(message.params(), TurnSteerParams.class));
            case "skills/list" -> session.skillsList(readParams(message.params(), SkillsListParams.class));
            default -> throw new NoSuchMethodException("Method not found: " + message.method());
        };
    }

    private void ensureNotificationSubscription() {
        if (subscription != null) {
            return;
        }
        try {
            subscription = session.subscribe(this::handleNotification);
        }
        catch (Exception exception) {
            throw new IllegalStateException("Unable to subscribe to app-server notifications.", exception);
        }
    }

    private void handleNotification(AppServerNotification notification) {
        JsonRpcNotificationMessage outbound = new JsonRpcNotificationMessage(
                "2.0",
                notification.method(),
                objectMapper.valueToTree(notification));
        synchronized (this) {
            if (dispatchingRequest) {
                pendingNotifications.add(outbound);
                return;
            }
        }
        try {
            writer.writeNotification(outbound);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to write app-server notification.", exception);
        }
    }

    private void flushPendingNotifications() throws IOException {
        if (pendingNotifications.isEmpty()) {
            return;
        }
        List<JsonRpcNotificationMessage> notifications = List.copyOf(pendingNotifications);
        pendingNotifications.clear();
        for (JsonRpcNotificationMessage notification : notifications) {
            writer.writeNotification(notification);
        }
    }

    private <T> T readParams(JsonNode params, Class<T> type) throws IOException {
        if (params == null || params.isNull()) {
            if (type == InitializedNotification.class) {
                return type.cast(new InitializedNotification());
            }
            return null;
        }
        try {
            return objectMapper.treeToValue(params, type);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid params for " + type.getSimpleName(), exception);
        }
    }

    private void writeError(JsonNode requestId, int code, String message) throws IOException {
        writer.writeResponse(new JsonRpcResponseMessage(
                "2.0",
                requestId,
                null,
                new JsonRpcError(code, message == null || message.isBlank() ? "Unknown error" : message, null)));
    }
}
