package org.dean.codex.runtime.springai.appserver;

import org.dean.codex.core.appserver.CodexAppServer;
import org.dean.codex.core.appserver.CodexAppServerSession;
import org.dean.codex.core.runtime.CodexRuntimeGateway;
import org.dean.codex.protocol.appserver.AppServerNotification;
import org.dean.codex.protocol.appserver.InitializeParams;
import org.dean.codex.protocol.appserver.InitializeResponse;
import org.dean.codex.protocol.appserver.InitializedNotification;
import org.dean.codex.protocol.appserver.SkillsListParams;
import org.dean.codex.protocol.appserver.SkillsListResponse;
import org.dean.codex.protocol.appserver.ThreadCompactStartParams;
import org.dean.codex.protocol.appserver.ThreadCompactStartResponse;
import org.dean.codex.protocol.appserver.ThreadCompactedNotification;
import org.dean.codex.protocol.appserver.ThreadListResponse;
import org.dean.codex.protocol.appserver.ThreadReadParams;
import org.dean.codex.protocol.appserver.ThreadReadResponse;
import org.dean.codex.protocol.appserver.ThreadResumeParams;
import org.dean.codex.protocol.appserver.ThreadResumeResponse;
import org.dean.codex.protocol.appserver.ThreadStartParams;
import org.dean.codex.protocol.appserver.ThreadStartResponse;
import org.dean.codex.protocol.appserver.ThreadStartedNotification;
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
import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.ThreadSummary;
import org.dean.codex.protocol.runtime.RuntimeNotification;
import org.dean.codex.protocol.runtime.RuntimeNotificationType;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class InProcessCodexAppServer implements CodexAppServer {

    private final CodexRuntimeGateway runtimeGateway;

    public InProcessCodexAppServer(CodexRuntimeGateway runtimeGateway) {
        this.runtimeGateway = runtimeGateway;
    }

    @Override
    public CodexAppServerSession connect() {
        return new Session();
    }

    private final class Session implements CodexAppServerSession {

        private final CopyOnWriteArrayList<Consumer<AppServerNotification>> subscribers = new CopyOnWriteArrayList<>();
        private final Map<ThreadId, AutoCloseable> runtimeSubscriptions = new ConcurrentHashMap<>();
        private final Set<String> optOutNotificationMethods = new HashSet<>();
        private boolean initializeCalled;
        private boolean initializedAcknowledged;

        @Override
        public synchronized InitializeResponse initialize(InitializeParams params) {
            if (initializeCalled) {
                throw new IllegalStateException("Already initialized");
            }
            initializeCalled = true;
            optOutNotificationMethods.clear();
            if (params != null && params.capabilities() != null) {
                optOutNotificationMethods.addAll(params.capabilities().optOutNotificationMethods());
            }
            return new InitializeResponse(
                    buildUserAgent(params),
                    Path.of(System.getProperty("user.home"), ".codex-java").toAbsolutePath().normalize().toString(),
                    "desktop",
                    System.getProperty("os.name", "unknown"));
        }

        @Override
        public synchronized void initialized(InitializedNotification notification) {
            if (!initializeCalled) {
                throw new IllegalStateException("Not initialized");
            }
            initializedAcknowledged = true;
        }

        @Override
        public ThreadStartResponse threadStart(ThreadStartParams params) {
            ensureReady();
            ThreadSummary thread = runtimeGateway.threadStart(params == null ? "" : params.title());
            ensureRuntimeSubscription(thread.threadId());
            publish(new ThreadStartedNotification(thread));
            return new ThreadStartResponse(thread);
        }

        @Override
        public ThreadResumeResponse threadResume(ThreadResumeParams params) {
            ensureReady();
            ThreadSummary thread = runtimeGateway.threadResume(requireThreadId(params));
            ensureRuntimeSubscription(thread.threadId());
            return new ThreadResumeResponse(thread);
        }

        @Override
        public ThreadListResponse threadList() {
            ensureReady();
            return new ThreadListResponse(runtimeGateway.listThreads());
        }

        @Override
        public ThreadReadResponse threadRead(ThreadReadParams params) {
            ensureReady();
            ThreadId threadId = requireThreadId(params);
            ThreadSummary thread = runtimeGateway.listThreads().stream()
                    .filter(summary -> summary.threadId().equals(threadId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown thread id: " + threadId.value()));
            return new ThreadReadResponse(
                    thread,
                    runtimeGateway.turns(threadId),
                    runtimeGateway.latestThreadMemory(threadId).orElse(null),
                    runtimeGateway.reconstructThreadContext(threadId));
        }

        @Override
        public ThreadCompactStartResponse threadCompactStart(ThreadCompactStartParams params) {
            ensureReady();
            ThreadId threadId = requireThreadId(params);
            var threadMemory = runtimeGateway.compactThread(threadId);
            publish(new ThreadCompactedNotification(threadId, threadMemory));
            return new ThreadCompactStartResponse(threadMemory);
        }

        @Override
        public TurnStartResponse turnStart(TurnStartParams params) {
            ensureReady();
            ThreadId threadId = requireThreadId(params);
            ensureRuntimeSubscription(threadId);
            return new TurnStartResponse(runtimeGateway.turnStart(threadId, params.input()));
        }

        @Override
        public TurnResumeResponse turnResume(TurnResumeParams params) {
            ensureReady();
            ThreadId threadId = requireThreadId(params);
            ensureRuntimeSubscription(threadId);
            return new TurnResumeResponse(runtimeGateway.turnResume(threadId, params.turnId()));
        }

        @Override
        public TurnInterruptResponse turnInterrupt(TurnInterruptParams params) {
            ensureReady();
            boolean accepted = runtimeGateway.turnInterrupt(requireThreadId(params), params.turnId());
            return new TurnInterruptResponse(params.turnId(), accepted);
        }

        @Override
        public TurnSteerResponse turnSteer(TurnSteerParams params) {
            ensureReady();
            boolean accepted = runtimeGateway.turnSteer(requireThreadId(params), params.turnId(), params.input());
            return new TurnSteerResponse(params.turnId(), accepted);
        }

        @Override
        public SkillsListResponse skillsList(SkillsListParams params) {
            ensureReady();
            return new SkillsListResponse(runtimeGateway.listSkills(params != null && params.forceReload()));
        }

        @Override
        public AutoCloseable subscribe(Consumer<AppServerNotification> listener) {
            ensureReady();
            subscribers.add(listener);
            return () -> subscribers.remove(listener);
        }

        @Override
        public void close() throws Exception {
            for (AutoCloseable runtimeSubscription : runtimeSubscriptions.values()) {
                runtimeSubscription.close();
            }
            runtimeSubscriptions.clear();
            subscribers.clear();
        }

        private void ensureReady() {
            if (!initializeCalled || !initializedAcknowledged) {
                throw new IllegalStateException("Not initialized");
            }
        }

        private void ensureRuntimeSubscription(ThreadId threadId) {
            runtimeSubscriptions.computeIfAbsent(threadId, ignored -> {
                try {
                    return runtimeGateway.subscribe(threadId, this::publishRuntimeNotification);
                }
                catch (Exception exception) {
                    throw new IllegalStateException("Unable to subscribe to runtime notifications for thread " + threadId.value(), exception);
                }
            });
        }

        private void publishRuntimeNotification(RuntimeNotification notification) {
            if (notification == null || notification.turn() == null) {
                return;
            }
            AppServerNotification mapped = switch (notification.type()) {
                case TURN_STARTED -> new TurnStartedNotification(notification.turn());
                case TURN_ITEM -> new TurnItemNotification(notification.turn(), notification.item());
                case TURN_COMPLETED -> new TurnCompletedNotification(notification.turn(), notification.finalAnswer());
                case THREAD_STARTED, THREAD_RESUMED -> null;
            };
            if (mapped != null) {
                publish(mapped);
            }
        }

        private void publish(AppServerNotification notification) {
            if (notification == null || optOutNotificationMethods.contains(notification.method())) {
                return;
            }
            for (Consumer<AppServerNotification> subscriber : subscribers) {
                try {
                    subscriber.accept(notification);
                }
                catch (Exception ignored) {
                    // App-server subscribers should not break the runtime.
                }
            }
        }

        private String buildUserAgent(InitializeParams params) {
            if (params == null || params.clientInfo() == null || params.clientInfo().name() == null || params.clientInfo().name().isBlank()) {
                return "codex-java-app-server";
            }
            return params.clientInfo().name().trim();
        }
    }

    private ThreadId requireThreadId(ThreadResumeParams params) {
        if (params == null || params.threadId() == null) {
            throw new IllegalArgumentException("threadId is required");
        }
        return params.threadId();
    }

    private ThreadId requireThreadId(ThreadReadParams params) {
        if (params == null || params.threadId() == null) {
            throw new IllegalArgumentException("threadId is required");
        }
        return params.threadId();
    }

    private ThreadId requireThreadId(ThreadCompactStartParams params) {
        if (params == null || params.threadId() == null) {
            throw new IllegalArgumentException("threadId is required");
        }
        return params.threadId();
    }

    private ThreadId requireThreadId(TurnStartParams params) {
        if (params == null || params.threadId() == null) {
            throw new IllegalArgumentException("threadId is required");
        }
        return params.threadId();
    }

    private ThreadId requireThreadId(TurnInterruptParams params) {
        if (params == null || params.threadId() == null) {
            throw new IllegalArgumentException("threadId is required");
        }
        return params.threadId();
    }

    private ThreadId requireThreadId(TurnSteerParams params) {
        if (params == null || params.threadId() == null) {
            throw new IllegalArgumentException("threadId is required");
        }
        return params.threadId();
    }

    private ThreadId requireThreadId(TurnResumeParams params) {
        if (params == null || params.threadId() == null) {
            throw new IllegalArgumentException("threadId is required");
        }
        return params.threadId();
    }
}
