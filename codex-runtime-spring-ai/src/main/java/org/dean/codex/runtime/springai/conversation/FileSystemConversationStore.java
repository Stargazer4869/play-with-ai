package org.dean.codex.runtime.springai.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.ThreadSummary;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.event.TurnEvent;
import org.dean.codex.protocol.item.TurnItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public class FileSystemConversationStore implements ConversationStore {

    private static final String THREADS_DIRECTORY = "threads";
    private static final String THREAD_METADATA_FILE = "thread.json";

    private final ObjectMapper objectMapper;
    private final Path storageRoot;
    private final Path threadsRoot;

    public FileSystemConversationStore(Path storageRoot) {
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
        this.threadsRoot = this.storageRoot.resolve(THREADS_DIRECTORY);
        try {
            Files.createDirectories(this.threadsRoot);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to initialize Codex storage at " + this.storageRoot, exception);
        }
    }

    @Override
    public synchronized ThreadId createThread(String title) {
        ThreadId threadId = new ThreadId(UUID.randomUUID().toString());
        Instant now = Instant.now();
        ThreadMetadata metadata = new ThreadMetadata(
                threadId.value(),
                title == null || title.isBlank() ? "New thread" : title.trim(),
                now,
                now);
        try {
            Files.createDirectories(turnsDirectory(threadId));
            writeJson(threadMetadataFile(threadId), metadata);
            return threadId;
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to create thread " + threadId.value(), exception);
        }
    }

    @Override
    public synchronized boolean exists(ThreadId threadId) {
        return threadId != null && Files.exists(threadMetadataFile(threadId));
    }

    @Override
    public synchronized TurnId startTurn(ThreadId threadId, String userInput, Instant startedAt) {
        requireThread(threadId);
        TurnId turnId = new TurnId(UUID.randomUUID().toString());
        ConversationTurn turn = new ConversationTurn(
                turnId,
                threadId,
                userInput == null ? "" : userInput,
                "",
                TurnStatus.RUNNING,
                startedAt == null ? Instant.now() : startedAt,
                null,
                List.of(),
                List.of());
        try {
            writeJson(turnFile(threadId, turnId), turn);
            touchThread(threadId, turn.startedAt());
            return turnId;
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to start turn " + turnId.value(), exception);
        }
    }

    @Override
    public synchronized void appendTurnEvents(ThreadId threadId, TurnId turnId, List<TurnEvent> events) {
        requireThread(threadId);
        if (events == null || events.isEmpty()) {
            return;
        }
        ConversationTurn turn = readTurn(threadId, turnId);
        ConversationTurn updatedTurn = new ConversationTurn(
                turn.turnId(),
                turn.threadId(),
                turn.userInput(),
                turn.finalAnswer(),
                turn.status(),
                turn.startedAt(),
                turn.completedAt(),
                Stream.concat(turn.events().stream(), events.stream()).toList(),
                turn.items());
        try {
            writeJson(turnFile(threadId, turnId), updatedTurn);
            Instant updatedAt = events.get(events.size() - 1).createdAt();
            touchThread(threadId, updatedAt == null ? Instant.now() : updatedAt);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to append events to turn " + turnId.value(), exception);
        }
    }

    @Override
    public synchronized void appendTurnItems(ThreadId threadId, TurnId turnId, List<TurnItem> items) {
        requireThread(threadId);
        if (items == null || items.isEmpty()) {
            return;
        }
        ConversationTurn turn = readTurn(threadId, turnId);
        ConversationTurn updatedTurn = new ConversationTurn(
                turn.turnId(),
                turn.threadId(),
                turn.userInput(),
                turn.finalAnswer(),
                turn.status(),
                turn.startedAt(),
                turn.completedAt(),
                turn.events(),
                Stream.concat(turn.items().stream(), items.stream()).toList());
        try {
            writeJson(turnFile(threadId, turnId), updatedTurn);
            Instant updatedAt = items.get(items.size() - 1).createdAt();
            touchThread(threadId, updatedAt == null ? Instant.now() : updatedAt);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to append items to turn " + turnId.value(), exception);
        }
    }

    @Override
    public synchronized void updateTurnStatus(ThreadId threadId, TurnId turnId, TurnStatus status, Instant updatedAt) {
        requireThread(threadId);
        ConversationTurn turn = readTurn(threadId, turnId);
        ConversationTurn updatedTurn = new ConversationTurn(
                turn.turnId(),
                turn.threadId(),
                turn.userInput(),
                turn.finalAnswer(),
                status == null ? turn.status() : status,
                turn.startedAt(),
                isTerminal(status) ? (updatedAt == null ? Instant.now() : updatedAt) : null,
                turn.events(),
                turn.items());
        try {
            writeJson(turnFile(threadId, turnId), updatedTurn);
            touchThread(threadId, updatedAt == null ? Instant.now() : updatedAt);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to update turn status for " + turnId.value(), exception);
        }
    }

    @Override
    public synchronized void completeTurn(ThreadId threadId, TurnId turnId, TurnStatus status, String finalAnswer, Instant completedAt) {
        requireThread(threadId);
        ConversationTurn turn = readTurn(threadId, turnId);
        ConversationTurn updatedTurn = new ConversationTurn(
                turn.turnId(),
                turn.threadId(),
                turn.userInput(),
                finalAnswer == null ? "" : finalAnswer,
                status == null ? TurnStatus.COMPLETED : status,
                turn.startedAt(),
                completedAt == null ? Instant.now() : completedAt,
                turn.events(),
                turn.items());
        try {
            writeJson(turnFile(threadId, turnId), updatedTurn);
            touchThread(threadId, updatedTurn.completedAt());
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to complete turn " + turnId.value(), exception);
        }
    }

    @Override
    public synchronized ConversationTurn turn(ThreadId threadId, TurnId turnId) {
        requireThread(threadId);
        return readTurn(threadId, turnId);
    }

    @Override
    public synchronized List<ConversationTurn> turns(ThreadId threadId) {
        requireThread(threadId);
        try (Stream<Path> stream = Files.list(turnsDirectory(threadId))) {
            return stream
                    .filter(path -> path.toString().endsWith(".json"))
                    .sorted()
                    .map(this::readTurnFile)
                    .sorted(Comparator.comparing(ConversationTurn::startedAt))
                    .toList();
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to read turns for thread " + threadId.value(), exception);
        }
    }

    @Override
    public synchronized List<ThreadSummary> listThreads() {
        try (Stream<Path> stream = Files.list(threadsRoot)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(this::toThreadSummary)
                    .sorted(Comparator.comparing(ThreadSummary::updatedAt).reversed())
                    .toList();
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to list Codex threads in " + threadsRoot, exception);
        }
    }

    private ThreadMetadata requireThread(ThreadId threadId) {
        if (!exists(threadId)) {
            throw new IllegalArgumentException("Unknown thread id: " + (threadId == null ? "<null>" : threadId.value()));
        }
        return readThreadMetadata(threadId);
    }

    private ThreadSummary toThreadSummary(Path threadDirectory) {
        ThreadId threadId = new ThreadId(threadDirectory.getFileName().toString());
        ThreadMetadata metadata = readThreadMetadata(threadId);
        int turnCount;
        try (Stream<Path> stream = Files.list(turnsDirectory(threadId))) {
            turnCount = (int) stream.filter(path -> path.toString().endsWith(".json")).count();
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to count turns for thread " + threadId.value(), exception);
        }
        return new ThreadSummary(threadId, metadata.title(), metadata.createdAt(), metadata.updatedAt(), turnCount);
    }

    private ThreadMetadata readThreadMetadata(ThreadId threadId) {
        try {
            return objectMapper.readValue(threadMetadataFile(threadId).toFile(), ThreadMetadata.class);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to read thread metadata for " + threadId.value(), exception);
        }
    }

    private ConversationTurn readTurn(ThreadId threadId, TurnId turnId) {
        Path path = turnFile(threadId, turnId);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Unknown turn id: " + (turnId == null ? "<null>" : turnId.value()));
        }
        return readTurnFile(path);
    }

    private ConversationTurn readTurnFile(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), ConversationTurn.class);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to read turn file " + path, exception);
        }
    }

    private void touchThread(ThreadId threadId, Instant updatedAt) throws IOException {
        ThreadMetadata metadata = readThreadMetadata(threadId);
        writeJson(threadMetadataFile(threadId), new ThreadMetadata(
                metadata.threadId(),
                metadata.title(),
                metadata.createdAt(),
                updatedAt == null ? Instant.now() : updatedAt));
    }

    private void writeJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Path tempFile = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), value);
            Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private Path threadDirectory(ThreadId threadId) {
        return threadsRoot.resolve(threadId.value());
    }

    private Path threadMetadataFile(ThreadId threadId) {
        return threadDirectory(threadId).resolve(THREAD_METADATA_FILE);
    }

    private Path turnsDirectory(ThreadId threadId) {
        return threadDirectory(threadId).resolve("turns");
    }

    private Path turnFile(ThreadId threadId, TurnId turnId) {
        return turnsDirectory(threadId).resolve(turnId.value() + ".json");
    }

    private boolean isTerminal(TurnStatus status) {
        return status == TurnStatus.COMPLETED
                || status == TurnStatus.FAILED
                || status == TurnStatus.INTERRUPTED;
    }

    private record ThreadMetadata(String threadId, String title, Instant createdAt, Instant updatedAt) {
    }
}
