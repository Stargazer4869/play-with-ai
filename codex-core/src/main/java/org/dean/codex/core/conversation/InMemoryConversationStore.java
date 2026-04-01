package org.dean.codex.core.conversation;

import org.dean.codex.protocol.appserver.ThreadForkParams;
import org.dean.codex.protocol.agent.AgentStatus;
import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.ThreadActiveFlag;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.ThreadSource;
import org.dean.codex.protocol.conversation.ThreadStatus;
import org.dean.codex.protocol.conversation.ThreadSummary;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.event.TurnEvent;
import org.dean.codex.protocol.item.TurnItem;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class InMemoryConversationStore implements ConversationStore {

    private final Map<ThreadId, ThreadRecord> threads = new LinkedHashMap<>();

    @Override
    public synchronized ThreadId createThread(String title) {
        ThreadId threadId = new ThreadId(UUID.randomUUID().toString());
        String safeTitle = title == null || title.isBlank() ? "New thread" : title.trim();
        Instant now = Instant.now();
        threads.put(threadId, ThreadRecord.create(threadId, safeTitle, now, defaultCwd()));
        return threadId;
    }

    @Override
    public synchronized ThreadId forkThread(ThreadForkParams params) {
        if (params == null || params.threadId() == null) {
            throw new IllegalArgumentException("threadId is required");
        }
        ThreadId sourceThreadId = params.threadId();
        ThreadRecord source = requireThread(sourceThreadId);
        ThreadId threadId = new ThreadId(UUID.randomUUID().toString());
        Instant now = Instant.now();
        ThreadSummary sourceSummary = toThreadSummary(sourceThreadId, source);
        ThreadSummary forkSummary = mergeTemplate(threadId, sourceSummary, params, now);
        threads.put(threadId, ThreadRecord.fromFork(
                threadId,
                forkSummary,
                now,
                forkedTurns(threadId, source.turns(), now)));
        return threadId;
    }

    @Override
    public synchronized ThreadSummary archiveThread(ThreadId threadId) {
        ThreadRecord record = requireThread(threadId);
        record.archivedAt = Instant.now();
        record.updatedAt = record.archivedAt;
        return toThreadSummary(threadId, record);
    }

    @Override
    public synchronized ThreadSummary unarchiveThread(ThreadId threadId) {
        ThreadRecord record = requireThread(threadId);
        record.archivedAt = null;
        record.updatedAt = Instant.now();
        return toThreadSummary(threadId, record);
    }

    @Override
    public synchronized ThreadSummary rollbackThread(ThreadId threadId, int numTurns) {
        if (numTurns < 1) {
            throw new IllegalArgumentException("numTurns must be >= 1");
        }
        ThreadRecord record = requireThread(threadId);
        if (record.turns().isEmpty()) {
            record.updatedAt = Instant.now();
            return toThreadSummary(threadId, record);
        }
        int removeCount = Math.min(numTurns, record.turns().size());
        if (removeCount > 0) {
            int newSize = record.turns().size() - removeCount;
            List<ConversationTurn> remainingTurns = new ArrayList<>(record.turns().subList(0, newSize));
            record.turns().clear();
            record.turns().addAll(remainingTurns);
            record.preview = remainingTurns.isEmpty()
                    ? record.title()
                    : previewText(remainingTurns.get(0).userInput(), record.title());
        }
        record.updatedAt = Instant.now();
        return toThreadSummary(threadId, record);
    }

    @Override
    public synchronized ThreadSummary updateAgentThread(ThreadId threadId,
                                                        ThreadId parentThreadId,
                                                        Integer agentDepth,
                                                        Instant agentClosedAt,
                                                        String agentNickname,
                                                        String agentRole,
                                                        String agentPath) {
        ThreadRecord record = requireThread(threadId);
        record.parentThreadId = parentThreadId;
        record.agentDepth = agentDepth;
        record.agentClosedAt = agentClosedAt;
        record.agentNickname = normalizeAgentField(agentNickname);
        record.agentRole = normalizeAgentField(agentRole);
        record.agentPath = normalizeAgentField(agentPath);
        record.updatedAt = Instant.now();
        return toThreadSummary(threadId, record);
    }

    @Override
    public synchronized boolean exists(ThreadId threadId) {
        return threads.containsKey(threadId);
    }

    @Override
    public synchronized TurnId startTurn(ThreadId threadId, String userInput, Instant startedAt) {
        ThreadRecord record = requireThread(threadId);
        TurnId turnId = new TurnId(UUID.randomUUID().toString());
        Instant safeStartedAt = startedAt == null ? Instant.now() : startedAt;
        if (record.turns().isEmpty()) {
            record.preview = previewText(userInput, record.title());
        }
        record.turns().add(new ConversationTurn(
                turnId,
                threadId,
                userInput == null ? "" : userInput,
                "",
                TurnStatus.RUNNING,
                safeStartedAt,
                null,
                List.of(),
                List.of()));
        record.updatedAt = safeStartedAt;
        return turnId;
    }

    @Override
    public synchronized void appendTurnEvents(ThreadId threadId, TurnId turnId, List<TurnEvent> events) {
        ThreadRecord record = requireThread(threadId);
        ConversationTurn turn = requireTurn(record, turnId);
        List<TurnEvent> mergedEvents = new ArrayList<>(turn.events());
        if (events != null) {
            mergedEvents.addAll(events);
        }
        replaceTurn(record, new ConversationTurn(
                turn.turnId(),
                turn.threadId(),
                turn.userInput(),
                turn.finalAnswer(),
                turn.status(),
                turn.startedAt(),
                turn.completedAt(),
                List.copyOf(mergedEvents),
                turn.items()));
        record.updatedAt = Instant.now();
    }

    @Override
    public synchronized void appendTurnItems(ThreadId threadId, TurnId turnId, List<TurnItem> items) {
        ThreadRecord record = requireThread(threadId);
        ConversationTurn turn = requireTurn(record, turnId);
        List<TurnItem> mergedItems = new ArrayList<>(turn.items());
        if (items != null) {
            mergedItems.addAll(items);
        }
        replaceTurn(record, new ConversationTurn(
                turn.turnId(),
                turn.threadId(),
                turn.userInput(),
                turn.finalAnswer(),
                turn.status(),
                turn.startedAt(),
                turn.completedAt(),
                turn.events(),
                List.copyOf(mergedItems)));
        record.updatedAt = Instant.now();
    }

    @Override
    public synchronized void updateTurnStatus(ThreadId threadId, TurnId turnId, TurnStatus status, Instant updatedAt) {
        ThreadRecord record = requireThread(threadId);
        ConversationTurn turn = requireTurn(record, turnId);
        replaceTurn(record, new ConversationTurn(
                turn.turnId(),
                turn.threadId(),
                turn.userInput(),
                turn.finalAnswer(),
                status == null ? turn.status() : status,
                turn.startedAt(),
                isTerminal(status) ? (updatedAt == null ? Instant.now() : updatedAt) : null,
                turn.events(),
                turn.items()));
        record.updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    @Override
    public synchronized void completeTurn(ThreadId threadId, TurnId turnId, TurnStatus status, String finalAnswer, Instant completedAt) {
        ThreadRecord record = requireThread(threadId);
        ConversationTurn turn = requireTurn(record, turnId);
        Instant safeCompletedAt = completedAt == null ? Instant.now() : completedAt;
        replaceTurn(record, new ConversationTurn(
                turn.turnId(),
                turn.threadId(),
                turn.userInput(),
                finalAnswer == null ? "" : finalAnswer,
                status == null ? TurnStatus.COMPLETED : status,
                turn.startedAt(),
                safeCompletedAt,
                turn.events(),
                turn.items()));
        record.updatedAt = safeCompletedAt;
    }

    @Override
    public synchronized ConversationTurn turn(ThreadId threadId, TurnId turnId) {
        return requireTurn(requireThread(threadId), turnId);
    }

    @Override
    public synchronized List<ConversationTurn> turns(ThreadId threadId) {
        return List.copyOf(requireThread(threadId).turns());
    }

    @Override
    public synchronized List<ThreadSummary> listThreads() {
        return threads.entrySet().stream()
                .map(entry -> toThreadSummary(entry.getKey(), entry.getValue()))
                .toList();
    }

    private String defaultCwd() {
        return java.nio.file.Path.of("").toAbsolutePath().normalize().toString();
    }

    private String previewText(String text, String fallback) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return fallback;
        }
        if (normalized.length() <= 120) {
            return normalized;
        }
        return normalized.substring(0, 117) + "...";
    }

    private String normalizeAgentField(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private ThreadRecord requireThread(ThreadId threadId) {
        ThreadRecord record = threads.get(threadId);
        if (record == null) {
            throw new IllegalArgumentException("Unknown thread id: " + (threadId == null ? "<null>" : threadId.value()));
        }
        return record;
    }

    private ConversationTurn requireTurn(ThreadRecord record, TurnId turnId) {
        return record.turns().stream()
                .filter(turn -> turn.turnId().equals(turnId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown turn id: " + (turnId == null ? "<null>" : turnId.value())));
    }

    private void replaceTurn(ThreadRecord record, ConversationTurn updatedTurn) {
        List<ConversationTurn> turns = record.turns();
        for (int index = 0; index < turns.size(); index++) {
            if (turns.get(index).turnId().equals(updatedTurn.turnId())) {
                turns.set(index, updatedTurn);
                return;
            }
        }
        throw new IllegalArgumentException("Unknown turn id: " + updatedTurn.turnId().value());
    }

    private boolean isTerminal(TurnStatus status) {
        return status == TurnStatus.COMPLETED
                || status == TurnStatus.FAILED
                || status == TurnStatus.INTERRUPTED;
    }

    private List<ConversationTurn> forkedTurns(ThreadId forkedThreadId, List<ConversationTurn> sourceTurns, Instant forkedAt) {
        if (sourceTurns == null || sourceTurns.isEmpty()) {
            return new ArrayList<>();
        }
        List<ConversationTurn> copiedTurns = new ArrayList<>(sourceTurns.size());
        for (ConversationTurn turn : sourceTurns) {
            TurnStatus forkedStatus = normalizeForkedStatus(turn.status());
            copiedTurns.add(new ConversationTurn(
                    turn.turnId(),
                    forkedThreadId,
                    turn.userInput(),
                    turn.finalAnswer(),
                    forkedStatus,
                    turn.startedAt(),
                    isTerminal(forkedStatus) ? (turn.completedAt() == null ? forkedAt : turn.completedAt()) : turn.completedAt(),
                    List.copyOf(turn.events()),
                    List.copyOf(turn.items())));
        }
        return copiedTurns;
    }

    private TurnStatus normalizeForkedStatus(TurnStatus status) {
        if (status == TurnStatus.RUNNING || status == TurnStatus.AWAITING_APPROVAL) {
            return TurnStatus.INTERRUPTED;
        }
        return status;
    }

    private static final class ThreadRecord {
        private final ThreadId threadId;
        private final String title;
        private final Instant createdAt;
        private Instant updatedAt;
        private String preview;
        private final boolean ephemeral;
        private String modelProvider;
        private String model;
        private final String cwd;
        private final ThreadSource source;
        private final boolean materialized;
        private Instant archivedAt;
        private String agentNickname;
        private String agentRole;
        private String agentPath;
        private ThreadId parentThreadId;
        private Integer agentDepth;
        private Instant agentClosedAt;
        private final List<ConversationTurn> turns;

        private ThreadRecord(String title,
                             ThreadId threadId,
                             Instant createdAt,
                             Instant updatedAt,
                             String preview,
                             boolean ephemeral,
                             String modelProvider,
                             String model,
                             String cwd,
                             ThreadSource source,
                             boolean materialized,
                             Instant archivedAt,
                             String agentNickname,
                             String agentRole,
                             String agentPath,
                             ThreadId parentThreadId,
                             Integer agentDepth,
                             Instant agentClosedAt,
                             List<ConversationTurn> turns) {
            this.threadId = threadId;
            this.title = title;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.preview = preview;
            this.ephemeral = ephemeral;
            this.modelProvider = modelProvider;
            this.model = model;
            this.cwd = cwd;
            this.source = source;
            this.materialized = materialized;
            this.archivedAt = archivedAt;
            this.agentNickname = agentNickname;
            this.agentRole = agentRole;
            this.agentPath = agentPath;
            this.parentThreadId = parentThreadId;
            this.agentDepth = agentDepth;
            this.agentClosedAt = agentClosedAt;
            this.turns = turns;
        }

        private static ThreadRecord create(ThreadId threadId, String title, Instant now, String cwd) {
            return new ThreadRecord(
                    title,
                    threadId,
                    now,
                    now,
                    title,
                    false,
                    null,
                    null,
                    cwd,
                    ThreadSource.UNKNOWN,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    new ArrayList<>());
        }

        private static ThreadRecord fromFork(ThreadId threadId, ThreadSummary summary, Instant now, List<ConversationTurn> turns) {
            return new ThreadRecord(
                    summary.title(),
                    threadId,
                    now,
                    now,
                    summary.preview(),
                    summary.ephemeral(),
                    summary.modelProvider(),
                    summary.model(),
                    summary.cwd() == null ? Path.of("").toAbsolutePath().normalize().toString() : summary.cwd(),
                    summary.source(),
                    summary.materialized(),
                    null,
                    summary.agentNickname(),
                    summary.agentRole(),
                    summary.agentPath(),
                    null,
                    null,
                    null,
                    turns);
        }

        private String title() {
            return title;
        }

        private Instant createdAt() {
            return createdAt;
        }

        private Instant updatedAt() {
            return updatedAt;
        }

        private String preview() {
            return preview;
        }

        private boolean ephemeral() {
            return ephemeral;
        }

        private String modelProvider() {
            return modelProvider;
        }

        private String model() {
            return model;
        }

        private String cwd() {
            return cwd;
        }

        private ThreadSource source() {
            return source;
        }

        private boolean materialized() {
            return materialized;
        }

        private Instant archivedAt() {
            return archivedAt;
        }

        private String agentNickname() {
            return agentNickname;
        }

        private String agentRole() {
            return agentRole;
        }

        private String agentPath() {
            return agentPath;
        }

        private ThreadId parentThreadId() {
            return parentThreadId;
        }

        private Integer agentDepth() {
            return agentDepth;
        }

        private Instant agentClosedAt() {
            return agentClosedAt;
        }

        private List<ConversationTurn> turns() {
            return turns;
        }
    }

    private ThreadSummary toThreadSummary(ThreadId threadId, ThreadRecord record) {
        return new ThreadSummary(
                threadId,
                record.title(),
                record.createdAt(),
                record.updatedAt(),
                record.turns().size(),
                record.preview(),
                record.ephemeral(),
                record.modelProvider(),
                record.model(),
                ThreadStatus.NOT_LOADED,
                List.of(),
                null,
                record.cwd(),
                record.source(),
                record.materialized(),
                record.archivedAt(),
                record.agentNickname(),
                record.agentRole(),
                record.agentPath(),
                record.parentThreadId(),
                record.agentDepth(),
                agentStatus(record),
                record.agentClosedAt());
    }

    private ThreadSummary mergeTemplate(ThreadId threadId, ThreadSummary sourceSummary, ThreadForkParams template, Instant now) {
        return new ThreadSummary(
                threadId,
                template.title() == null ? sourceSummary.title() : template.title(),
                now,
                now,
                sourceSummary.turnCount(),
                sourceSummary.preview(),
                template.ephemeral() == null ? sourceSummary.ephemeral() : template.ephemeral(),
                template.modelProvider() == null ? sourceSummary.modelProvider() : template.modelProvider(),
                template.model() == null ? sourceSummary.model() : template.model(),
                sourceSummary.status(),
                sourceSummary.activeFlags(),
                sourceSummary.path(),
                template.cwd() == null ? sourceSummary.cwd() : template.cwd(),
                template.source() == null ? sourceSummary.source() : template.source(),
                sourceSummary.materialized(),
                null,
                template.agentNickname() == null ? sourceSummary.agentNickname() : template.agentNickname(),
                template.agentRole() == null ? sourceSummary.agentRole() : template.agentRole(),
                template.agentPath() == null ? sourceSummary.agentPath() : template.agentPath(),
                null,
                null,
                null,
                null);
    }

    private AgentStatus agentStatus(ThreadRecord record) {
        if (record == null || (record.parentThreadId() == null && record.agentPath() == null && record.agentNickname() == null)) {
            return null;
        }
        if (record.agentClosedAt() != null) {
            return AgentStatus.SHUTDOWN;
        }
        return AgentStatus.IDLE;
    }
}
