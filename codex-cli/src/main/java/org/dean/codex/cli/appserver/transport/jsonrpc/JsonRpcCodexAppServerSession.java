package org.dean.codex.cli.appserver.transport.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.LongNode;
import org.dean.codex.core.appserver.CodexAppServerSession;
import org.dean.codex.protocol.appserver.AppServerNotification;
import org.dean.codex.protocol.appserver.InitializeParams;
import org.dean.codex.protocol.appserver.InitializeResponse;
import org.dean.codex.protocol.appserver.InitializedNotification;
import org.dean.codex.protocol.appserver.SkillsListParams;
import org.dean.codex.protocol.appserver.SkillsListResponse;
import org.dean.codex.protocol.appserver.ThreadArchiveParams;
import org.dean.codex.protocol.appserver.ThreadArchiveResponse;
import org.dean.codex.protocol.appserver.ThreadCompactionStartedNotification;
import org.dean.codex.protocol.appserver.ThreadCompactStartParams;
import org.dean.codex.protocol.appserver.ThreadCompactStartResponse;
import org.dean.codex.protocol.appserver.ThreadCompactedNotification;
import org.dean.codex.protocol.appserver.ThreadForkParams;
import org.dean.codex.protocol.appserver.ThreadForkResponse;
import org.dean.codex.protocol.appserver.ThreadListParams;
import org.dean.codex.protocol.appserver.ThreadListResponse;
import org.dean.codex.protocol.appserver.ThreadLoadedListParams;
import org.dean.codex.protocol.appserver.ThreadLoadedListResponse;
import org.dean.codex.protocol.appserver.ThreadReadParams;
import org.dean.codex.protocol.appserver.ThreadReadResponse;
import org.dean.codex.protocol.appserver.ThreadRollbackParams;
import org.dean.codex.protocol.appserver.ThreadRollbackResponse;
import org.dean.codex.protocol.appserver.ThreadResumeParams;
import org.dean.codex.protocol.appserver.ThreadResumeResponse;
import org.dean.codex.protocol.appserver.ThreadStartParams;
import org.dean.codex.protocol.appserver.ThreadStartResponse;
import org.dean.codex.protocol.appserver.ThreadStartedNotification;
import org.dean.codex.protocol.appserver.ThreadUnarchiveParams;
import org.dean.codex.protocol.appserver.ThreadUnarchiveResponse;
import org.dean.codex.protocol.appserver.TurnCompletedNotification;
import org.dean.codex.protocol.appserver.TurnInterruptParams;
import org.dean.codex.protocol.appserver.TurnInterruptResponse;
import org.dean.codex.protocol.appserver.TurnItemNotification;
import org.dean.codex.protocol.appserver.TurnResumeParams;
import org.dean.codex.protocol.appserver.TurnResumeResponse;
import org.dean.codex.protocol.appserver.TurnStartParams;
import org.dean.codex.protocol.appserver.TurnStartResponse;
import org.dean.codex.protocol.appserver.TurnStartedNotification;
import org.dean.codex.protocol.appserver.TurnSteerParams;
import org.dean.codex.protocol.appserver.TurnSteerResponse;
import org.dean.codex.protocol.appserver.jsonrpc.JsonRpcError;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class JsonRpcCodexAppServerSession implements CodexAppServerSession {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private static final int INVALID_PARAMS = -32602;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int SERVER_ERROR = -32000;

    private static final Map<String, Class<? extends AppServerNotification>> NOTIFICATION_TYPES = Map.of(
            "thread/started", ThreadStartedNotification.class,
            "thread/compaction/started", ThreadCompactionStartedNotification.class,
            "thread/compacted", ThreadCompactedNotification.class,
            "turn/started", TurnStartedNotification.class,
            "turn/completed", TurnCompletedNotification.class,
            "item/completed", TurnItemNotification.class);

    private final ObjectMapper objectMapper;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final AutoCloseable closeHook;
    private final Duration requestTimeout;
    private final AtomicLong nextRequestId = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<String, CompletableFuture<JsonRpcResponseMessage>> pendingResponses = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<AppServerNotification>> listeners = new CopyOnWriteArrayList<>();
    private final Object writeMonitor = new Object();
    private final Thread readLoopThread;
    private volatile RuntimeException terminalFailure;

    public JsonRpcCodexAppServerSession(InputStream inputStream,
                                        OutputStream outputStream,
                                        AutoCloseable closeHook,
                                        Duration requestTimeout) {
        this(OBJECT_MAPPER,
                new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8)),
                new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)),
                closeHook,
                requestTimeout);
    }

    JsonRpcCodexAppServerSession(ObjectMapper objectMapper,
                                 BufferedReader reader,
                                 BufferedWriter writer,
                                 AutoCloseable closeHook,
                                 Duration requestTimeout) {
        this.objectMapper = objectMapper;
        this.reader = reader;
        this.writer = writer;
        this.closeHook = closeHook;
        this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
        this.readLoopThread = new Thread(this::readLoop, "codex-cli-appserver-jsonrpc-read-loop");
        this.readLoopThread.setDaemon(true);
        this.readLoopThread.start();
    }

    @Override
    public InitializeResponse initialize(InitializeParams params) {
        return request("initialize", params, InitializeResponse.class);
    }

    @Override
    public void initialized(InitializedNotification notification) {
        notifyOnly("initialized", notification);
    }

    @Override
    public ThreadStartResponse threadStart(ThreadStartParams params) {
        return request("thread/start", params, ThreadStartResponse.class);
    }

    @Override
    public ThreadResumeResponse threadResume(ThreadResumeParams params) {
        return request("thread/resume", params, ThreadResumeResponse.class);
    }

    @Override
    public ThreadListResponse threadList(ThreadListParams params) {
        return request("thread/list", params, ThreadListResponse.class);
    }

    @Override
    public ThreadLoadedListResponse threadLoadedList(ThreadLoadedListParams params) {
        return request("thread/loaded/list", params, ThreadLoadedListResponse.class);
    }

    @Override
    public ThreadReadResponse threadRead(ThreadReadParams params) {
        return request("thread/read", params, ThreadReadResponse.class);
    }

    @Override
    public ThreadForkResponse threadFork(ThreadForkParams params) {
        return request("thread/fork", params, ThreadForkResponse.class);
    }

    @Override
    public ThreadArchiveResponse threadArchive(ThreadArchiveParams params) {
        return request("thread/archive", params, ThreadArchiveResponse.class);
    }

    @Override
    public ThreadUnarchiveResponse threadUnarchive(ThreadUnarchiveParams params) {
        return request("thread/unarchive", params, ThreadUnarchiveResponse.class);
    }

    @Override
    public ThreadRollbackResponse threadRollback(ThreadRollbackParams params) {
        return request("thread/rollback", params, ThreadRollbackResponse.class);
    }

    @Override
    public ThreadCompactStartResponse threadCompactStart(ThreadCompactStartParams params) {
        return request("thread/compact/start", params, ThreadCompactStartResponse.class);
    }

    @Override
    public TurnStartResponse turnStart(TurnStartParams params) {
        return request("turn/start", params, TurnStartResponse.class);
    }

    @Override
    public TurnResumeResponse turnResume(TurnResumeParams params) {
        return request("turn/resume", params, TurnResumeResponse.class);
    }

    @Override
    public TurnInterruptResponse turnInterrupt(TurnInterruptParams params) {
        return request("turn/interrupt", params, TurnInterruptResponse.class);
    }

    @Override
    public TurnSteerResponse turnSteer(TurnSteerParams params) {
        return request("turn/steer", params, TurnSteerResponse.class);
    }

    @Override
    public SkillsListResponse skillsList(SkillsListParams params) {
        return request("skills/list", params, SkillsListResponse.class);
    }

    @Override
    public AutoCloseable subscribe(Consumer<AppServerNotification> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @Override
    public void close() throws Exception {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException closedFailure = new IllegalStateException("App-server session closed.");
        pendingResponses.values().forEach(future -> future.completeExceptionally(closedFailure));
        pendingResponses.clear();
        closeHook.close();
        readLoopThread.join(1000);
    }

    private void readLoop() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(line);
                if (node.has("method") && !node.has("id")) {
                    handleNotification(node);
                    continue;
                }
                JsonRpcResponseMessage response = objectMapper.treeToValue(node, JsonRpcResponseMessage.class);
                CompletableFuture<JsonRpcResponseMessage> responseFuture = pendingResponses.remove(idKey(response.id()));
                if (responseFuture != null) {
                    responseFuture.complete(response);
                }
            }
            if (!closed.get()) {
                failSession(new IllegalStateException("App-server transport closed."));
            }
        }
        catch (Exception exception) {
            if (!closed.get()) {
                failSession(exception);
            }
        }
    }

    private void handleNotification(JsonNode node) throws IOException {
        String method = node.path("method").asText("");
        Class<? extends AppServerNotification> type = NOTIFICATION_TYPES.get(method);
        if (type == null) {
            return;
        }
        JsonNode params = node.path("params");
        if (params.isMissingNode() || params.isNull()) {
            return;
        }
        AppServerNotification notification = objectMapper.treeToValue(params, type);
        for (Consumer<AppServerNotification> listener : List.copyOf(listeners)) {
            try {
                listener.accept(notification);
            }
            catch (Exception ignored) {
                // Listener failures should not break transport processing.
            }
        }
    }

    private <T> T request(String method, Object params, Class<T> resultType) {
        ensureOpen();

        JsonNode requestId = LongNode.valueOf(nextRequestId.incrementAndGet());
        String idKey = idKey(requestId);
        CompletableFuture<JsonRpcResponseMessage> responseFuture = new CompletableFuture<>();
        pendingResponses.put(idKey, responseFuture);

        try {
            write(new JsonRpcRequestMessage("2.0",
                    requestId,
                    method,
                    params == null ? null : objectMapper.valueToTree(params)));
        }
        catch (Exception exception) {
            pendingResponses.remove(idKey);
            throw new IllegalStateException("Unable to write app-server request: " + method, exception);
        }

        JsonRpcResponseMessage response;
        try {
            response = awaitResponse(method, responseFuture);
        }
        finally {
            pendingResponses.remove(idKey);
        }
        if (response.error() != null) {
            throw mapError(method, response.error());
        }
        if (resultType == Void.class) {
            return null;
        }
        try {
            JsonNode result = response.result();
            if (result == null || result.isNull()) {
                return null;
            }
            return objectMapper.treeToValue(result, resultType);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to decode response for app-server method: " + method, exception);
        }
    }

    private void notifyOnly(String method, Object params) {
        ensureOpen();
        try {
            write(new JsonRpcRequestMessage("2.0",
                    null,
                    method,
                    params == null ? null : objectMapper.valueToTree(params)));
        }
        catch (Exception exception) {
            throw new IllegalStateException("Unable to write app-server notification: " + method, exception);
        }
    }

    private JsonRpcResponseMessage awaitResponse(String method,
                                                 CompletableFuture<JsonRpcResponseMessage> responseFuture) {
        try {
            long timeoutMillis = Math.max(1, requestTimeout.toMillis());
            return responseFuture.get(timeoutMillis, TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException exception) {
            throw new IllegalStateException("Timed out waiting for app-server response: " + method, exception);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for app-server response: " + method, exception);
        }
        catch (ExecutionException exception) {
            throw unwrapExecutionFailure(method, exception);
        }
    }

    private RuntimeException unwrapExecutionFailure(String method, ExecutionException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException("App-server request failed: " + method, cause);
    }

    private RuntimeException mapError(String method, JsonRpcError error) {
        String errorMessage = "App-server error for " + method + ": " + error.message() + " (code " + error.code() + ")";
        if (error.code() == INVALID_PARAMS) {
            return new IllegalArgumentException(errorMessage);
        }
        if (error.code() == METHOD_NOT_FOUND) {
            return new UnsupportedOperationException(errorMessage);
        }
        if (error.code() == SERVER_ERROR) {
            return new IllegalStateException(errorMessage);
        }
        return new IllegalStateException(errorMessage);
    }

    private void write(JsonRpcRequestMessage message) throws IOException {
        synchronized (writeMonitor) {
            writer.write(objectMapper.writeValueAsString(message));
            writer.newLine();
            writer.flush();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("App-server session is already closed.");
        }
        if (terminalFailure != null) {
            throw terminalFailure;
        }
    }

    private void failSession(Exception exception) {
        RuntimeException failure = exception instanceof RuntimeException runtimeException
                ? runtimeException
                : new IllegalStateException("App-server transport failed.", exception);
        terminalFailure = failure;
        pendingResponses.values().forEach(responseFuture -> responseFuture.completeExceptionally(failure));
        pendingResponses.clear();
    }

    private String idKey(JsonNode id) {
        return id == null || id.isNull() ? "" : id.toString();
    }
}
