package org.dean.codex.core.conversation;

import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.ThreadSummary;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.event.TurnEvent;

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
        threads.put(threadId, new ThreadRecord(safeTitle, now, now, new ArrayList<>()));
        return threadId;
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
        record.turns().add(new ConversationTurn(
                turnId,
                threadId,
                userInput == null ? "" : userInput,
                "",
                TurnStatus.RUNNING,
                safeStartedAt,
                null,
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
                List.copyOf(mergedEvents)));
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
                status == TurnStatus.COMPLETED || status == TurnStatus.FAILED ? (updatedAt == null ? Instant.now() : updatedAt) : null,
                turn.events()));
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
                turn.events()));
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
                .map(entry -> new ThreadSummary(
                        entry.getKey(),
                        entry.getValue().title(),
                        entry.getValue().createdAt(),
                        entry.getValue().updatedAt(),
                        entry.getValue().turns().size()))
                .toList();
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

    private static final class ThreadRecord {
        private final String title;
        private final Instant createdAt;
        private Instant updatedAt;
        private final List<ConversationTurn> turns;

        private ThreadRecord(String title, Instant createdAt, Instant updatedAt, List<ConversationTurn> turns) {
            this.title = title;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.turns = turns;
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

        private List<ConversationTurn> turns() {
            return turns;
        }
    }
}
