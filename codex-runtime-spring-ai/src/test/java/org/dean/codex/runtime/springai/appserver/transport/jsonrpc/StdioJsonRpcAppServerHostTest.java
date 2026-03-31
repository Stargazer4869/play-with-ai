package org.dean.codex.runtime.springai.appserver.transport.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import org.dean.codex.core.appserver.CodexAppServer;
import org.dean.codex.core.appserver.CodexAppServerSession;
import org.dean.codex.protocol.appserver.AppServerClientInfo;
import org.dean.codex.protocol.appserver.AppServerNotification;
import org.dean.codex.protocol.appserver.InitializeParams;
import org.dean.codex.protocol.appserver.InitializeResponse;
import org.dean.codex.protocol.appserver.InitializedNotification;
import org.dean.codex.protocol.appserver.SkillsListParams;
import org.dean.codex.protocol.appserver.SkillsListResponse;
import org.dean.codex.protocol.appserver.ThreadCompactStartParams;
import org.dean.codex.protocol.appserver.ThreadCompactStartResponse;
import org.dean.codex.protocol.appserver.ThreadListResponse;
import org.dean.codex.protocol.appserver.ThreadReadParams;
import org.dean.codex.protocol.appserver.ThreadReadResponse;
import org.dean.codex.protocol.appserver.ThreadResumeParams;
import org.dean.codex.protocol.appserver.ThreadResumeResponse;
import org.dean.codex.protocol.appserver.ThreadStartParams;
import org.dean.codex.protocol.appserver.ThreadStartResponse;
import org.dean.codex.protocol.appserver.ThreadStartedNotification;
import org.dean.codex.protocol.appserver.TurnInterruptParams;
import org.dean.codex.protocol.appserver.TurnInterruptResponse;
import org.dean.codex.protocol.appserver.TurnResumeParams;
import org.dean.codex.protocol.appserver.TurnResumeResponse;
import org.dean.codex.protocol.appserver.TurnStartParams;
import org.dean.codex.protocol.appserver.TurnStartResponse;
import org.dean.codex.protocol.appserver.TurnStartedNotification;
import org.dean.codex.protocol.appserver.TurnSteerParams;
import org.dean.codex.protocol.appserver.TurnSteerResponse;
import org.dean.codex.protocol.appserver.jsonrpc.JsonRpcRequestMessage;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.ThreadSummary;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.runtime.RuntimeTurn;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StdioJsonRpcAppServerHostTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void stdioHostHandlesInitializeThreadStartTurnStartAndNotifications() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StdioJsonRpcAppServerHost host = new StdioJsonRpcAppServerHost(
                new JsonRpcAppServerDispatcher(new StubAppServer()),
                new ByteArrayInputStream(inputLines(
                        new JsonRpcRequestMessage("2.0", IntNode.valueOf(1), "initialize",
                                objectMapper.valueToTree(new InitializeParams(new AppServerClientInfo("test-client", "Test Client", "1.0.0"), null))),
                        new JsonRpcRequestMessage("2.0", null, "initialized", objectMapper.valueToTree(new InitializedNotification())),
                        new JsonRpcRequestMessage("2.0", IntNode.valueOf(2), "thread/start",
                                objectMapper.valueToTree(new ThreadStartParams("Demo thread"))),
                        new JsonRpcRequestMessage("2.0", IntNode.valueOf(3), "turn/start",
                                objectMapper.valueToTree(new TurnStartParams(new ThreadId("thread-1"), "Inspect repo"))))),
                output);

        host.run();

        List<JsonNode> messages = outputMessages(output);
        assertTrue(messages.stream().anyMatch(message ->
                message.path("id").asInt() == 1
                        && "test-client".equals(message.path("result").path("userAgent").asText())));
        assertTrue(messages.stream().anyMatch(message ->
                message.path("id").asInt() == 2
                        && "thread-1".equals(message.path("result").path("thread").path("threadId").path("value").asText())));
        assertTrue(messages.stream().anyMatch(message ->
                message.path("id").asInt() == 3
                        && "turn-1".equals(message.path("result").path("turn").path("turnId").path("value").asText())));
        assertTrue(messages.stream().anyMatch(message ->
                "turn/started".equals(message.path("method").asText())));
    }

    @Test
    void stdioHostReturnsErrorWhenRequestArrivesBeforeInitialize() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StdioJsonRpcAppServerHost host = new StdioJsonRpcAppServerHost(
                new JsonRpcAppServerDispatcher(new StubAppServer()),
                new ByteArrayInputStream(inputLines(
                        new JsonRpcRequestMessage("2.0", IntNode.valueOf(9), "thread/list", null))),
                output);

        host.run();

        List<JsonNode> messages = outputMessages(output);
        assertEquals(1, messages.size());
        assertEquals(-32000, messages.get(0).path("error").path("code").asInt());
        assertTrue(messages.get(0).path("error").path("message").asText().contains("Not initialized"));
    }

    private byte[] inputLines(JsonRpcRequestMessage... messages) throws Exception {
        StringBuilder builder = new StringBuilder();
        for (JsonRpcRequestMessage message : messages) {
            builder.append(objectMapper.writeValueAsString(message)).append(System.lineSeparator());
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    private List<JsonNode> outputMessages(ByteArrayOutputStream output) throws Exception {
        List<JsonNode> messages = new ArrayList<>();
        for (String line : output.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (line == null || line.isBlank()) {
                continue;
            }
            messages.add(objectMapper.readTree(line));
        }
        return messages;
    }

    private static final class StubAppServer implements CodexAppServer {

        @Override
        public CodexAppServerSession connect() {
            return new StubSession();
        }
    }

    private static final class StubSession implements CodexAppServerSession {

        private final List<Consumer<AppServerNotification>> listeners = new ArrayList<>();
        private boolean initializeCalled;
        private boolean initializedAcknowledged;

        @Override
        public InitializeResponse initialize(InitializeParams params) {
            if (initializeCalled) {
                throw new IllegalStateException("Already initialized");
            }
            initializeCalled = true;
            return new InitializeResponse(params.clientInfo().name(), "/tmp/.codex-java", "desktop", "test");
        }

        @Override
        public void initialized(InitializedNotification notification) {
            if (!initializeCalled) {
                throw new IllegalStateException("Not initialized");
            }
            initializedAcknowledged = true;
        }

        @Override
        public ThreadStartResponse threadStart(ThreadStartParams params) {
            ensureReady();
            ThreadSummary thread = new ThreadSummary(new ThreadId("thread-1"), params.title(), Instant.parse("2026-03-31T00:00:00Z"), Instant.parse("2026-03-31T00:00:00Z"), 0);
            publish(new ThreadStartedNotification(thread));
            return new ThreadStartResponse(thread);
        }

        @Override
        public ThreadResumeResponse threadResume(ThreadResumeParams params) {
            ensureReady();
            return new ThreadResumeResponse(new ThreadSummary(params.threadId(), "Demo thread", Instant.now(), Instant.now(), 0));
        }

        @Override
        public ThreadListResponse threadList() {
            ensureReady();
            return new ThreadListResponse(List.of());
        }

        @Override
        public ThreadReadResponse threadRead(ThreadReadParams params) {
            ensureReady();
            return new ThreadReadResponse(new ThreadSummary(params.threadId(), "Demo thread", Instant.now(), Instant.now(), 0), List.of(), null, null);
        }

        @Override
        public ThreadCompactStartResponse threadCompactStart(ThreadCompactStartParams params) {
            ensureReady();
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public TurnStartResponse turnStart(TurnStartParams params) {
            ensureReady();
            RuntimeTurn turn = new RuntimeTurn(params.threadId(), new TurnId("turn-1"), TurnStatus.RUNNING, Instant.parse("2026-03-31T00:00:01Z"), null);
            publish(new TurnStartedNotification(turn));
            return new TurnStartResponse(turn);
        }

        @Override
        public TurnResumeResponse turnResume(TurnResumeParams params) {
            ensureReady();
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public TurnInterruptResponse turnInterrupt(TurnInterruptParams params) {
            ensureReady();
            return new TurnInterruptResponse(params.turnId(), true);
        }

        @Override
        public TurnSteerResponse turnSteer(TurnSteerParams params) {
            ensureReady();
            return new TurnSteerResponse(params.turnId(), true);
        }

        @Override
        public SkillsListResponse skillsList(SkillsListParams params) {
            ensureReady();
            return new SkillsListResponse(List.of());
        }

        @Override
        public AutoCloseable subscribe(Consumer<AppServerNotification> listener) {
            ensureReady();
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }

        @Override
        public void close() {
            listeners.clear();
        }

        private void publish(AppServerNotification notification) {
            for (Consumer<AppServerNotification> listener : List.copyOf(listeners)) {
                listener.accept(notification);
            }
        }

        private void ensureReady() {
            if (!initializeCalled || !initializedAcknowledged) {
                throw new IllegalStateException("Not initialized");
            }
        }
    }
}
