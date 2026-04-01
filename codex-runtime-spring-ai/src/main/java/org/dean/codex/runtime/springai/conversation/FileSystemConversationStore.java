package org.dean.codex.runtime.springai.conversation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dean.codex.core.conversation.ConversationStore;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
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
        String safeTitle = title == null || title.isBlank() ? "New thread" : title.trim();
        ThreadMetadata metadata = ThreadMetadata.create(threadId, safeTitle, now, defaultCwd());
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
    public synchronized ThreadId forkThread(ThreadForkParams params) {
        if (params == null || params.threadId() == null) {
            throw new IllegalArgumentException("threadId is required");
        }
        ThreadId sourceThreadId = params.threadId();
        ThreadMetadata sourceMetadata = readThreadMetadata(sourceThreadId);
        ThreadId forkedThreadId = new ThreadId(UUID.randomUUID().toString());
        Instant now = Instant.now();
        ThreadMetadata forkMetadata = sourceMetadata.forkedCopy(
                forkedThreadId,
                params.title(),
                params.ephemeral(),
                params.cwd(),
                params.modelProvider(),
                params.model(),
                params.source(),
                params.agentNickname(),
                params.agentRole(),
                params.agentPath(),
                now);
        try {
            Files.createDirectories(turnsDirectory(forkedThreadId));
            writeJson(threadMetadataFile(forkedThreadId), forkMetadata);
            for (ConversationTurn turn : turns(sourceThreadId)) {
                TurnStatus forkedStatus = normalizeForkedStatus(turn.status());
                ConversationTurn forkedTurn = new ConversationTurn(
                        turn.turnId(),
                        forkedThreadId,
                        turn.userInput(),
                        turn.finalAnswer(),
                        forkedStatus,
                        turn.startedAt(),
                        isTerminal(forkedStatus) ? (turn.completedAt() == null ? now : turn.completedAt()) : turn.completedAt(),
                        turn.events(),
                        turn.items());
                writeJson(turnFile(forkedThreadId, forkedTurn.turnId()), forkedTurn);
            }
            return forkedThreadId;
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to fork thread " + sourceThreadId.value(), exception);
        }
    }

    @Override
    public synchronized ThreadSummary archiveThread(ThreadId threadId) {
        ThreadMetadata metadata = requireThread(threadId);
        Instant now = Instant.now();
        ThreadMetadata archivedMetadata = metadata.withArchivedAt(now).withUpdatedAt(now);
        try {
            writeJson(threadMetadataFile(threadId), archivedMetadata);
            return toThreadSummary(threadId, archivedMetadata);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to archive thread " + threadId.value(), exception);
        }
    }

    @Override
    public synchronized ThreadSummary unarchiveThread(ThreadId threadId) {
        ThreadMetadata metadata = requireThread(threadId);
        Instant now = Instant.now();
        ThreadMetadata unarchivedMetadata = metadata.withArchivedAt(null).withUpdatedAt(now);
        try {
            writeJson(threadMetadataFile(threadId), unarchivedMetadata);
            return toThreadSummary(threadId, unarchivedMetadata);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to unarchive thread " + threadId.value(), exception);
        }
    }

    @Override
    public synchronized ThreadSummary rollbackThread(ThreadId threadId, int numTurns) {
        if (numTurns < 1) {
            throw new IllegalArgumentException("numTurns must be >= 1");
        }
        ThreadMetadata metadata = requireThread(threadId);
        List<ConversationTurn> currentTurns = turns(threadId);
        if (currentTurns.isEmpty()) {
            Instant now = Instant.now();
            ThreadMetadata updatedMetadata = metadata.withUpdatedAt(now).withPreview(metadata.title());
            try {
                writeJson(threadMetadataFile(threadId), updatedMetadata);
                return toThreadSummary(threadId, updatedMetadata);
            }
            catch (IOException exception) {
                throw new IllegalStateException("Unable to rollback thread " + threadId.value(), exception);
            }
        }

        int removeCount = Math.min(numTurns, currentTurns.size());
        int keepCount = currentTurns.size() - removeCount;
        List<ConversationTurn> remainingTurns = new ArrayList<>(currentTurns.subList(0, keepCount));
        List<ConversationTurn> removedTurns = new ArrayList<>(currentTurns.subList(keepCount, currentTurns.size()));
        Instant now = Instant.now();

        try {
            for (ConversationTurn removedTurn : removedTurns) {
                Files.deleteIfExists(turnFile(threadId, removedTurn.turnId()));
            }
            ThreadMetadata updatedMetadata = metadata
                    .withUpdatedAt(now)
                    .withPreview(remainingTurns.isEmpty()
                            ? metadata.title()
                            : previewText(remainingTurns.get(0).userInput(), metadata.title()));
            writeJson(threadMetadataFile(threadId), updatedMetadata);
            return toThreadSummary(threadId, updatedMetadata);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to rollback thread " + threadId.value(), exception);
        }
    }

    @Override
    public synchronized ThreadSummary updateAgentThread(ThreadId threadId,
                                                        ThreadId parentThreadId,
                                                        Integer agentDepth,
                                                        Instant agentClosedAt,
                                                        String agentNickname,
                                                        String agentRole,
                                                        String agentPath) {
        ThreadMetadata metadata = requireThread(threadId);
        Instant now = Instant.now();
        ThreadMetadata updatedMetadata = metadata
                .withParentThreadId(parentThreadId)
                .withAgentDepth(agentDepth)
                .withAgentClosedAt(agentClosedAt)
                .withAgentFields(agentNickname, agentRole, agentPath)
                .withUpdatedAt(now);
        try {
            writeJson(threadMetadataFile(threadId), updatedMetadata);
            return toThreadSummary(threadId, updatedMetadata);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to update agent metadata for thread " + threadId.value(), exception);
        }
    }

    @Override
    public synchronized boolean exists(ThreadId threadId) {
        return threadId != null && Files.exists(threadMetadataFile(threadId));
    }

    @Override
    public synchronized TurnId startTurn(ThreadId threadId, String userInput, Instant startedAt) {
        ThreadMetadata metadata = requireThread(threadId);
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
            writeJson(threadMetadataFile(threadId), metadata
                    .withUpdatedAt(turn.startedAt())
                    .withPreview(previewText(userInput, metadata.title())));
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
        return toThreadSummary(threadId, threadDirectory, metadata);
    }

    private ThreadSummary toThreadSummary(ThreadId threadId, ThreadMetadata metadata) {
        return toThreadSummary(threadId, threadDirectory(threadId), metadata);
    }

    private ThreadSummary toThreadSummary(ThreadId threadId, Path threadDirectory, ThreadMetadata metadata) {
        int turnCount;
        try (Stream<Path> stream = Files.list(turnsDirectory(threadId))) {
            turnCount = (int) stream.filter(path -> path.toString().endsWith(".json")).count();
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to count turns for thread " + threadId.value(), exception);
        }
        return new ThreadSummary(
                threadId,
                metadata.title(),
                metadata.createdAt(),
                metadata.updatedAt(),
                turnCount,
                effectivePreview(threadId, metadata),
                metadata.ephemeral(),
                metadata.modelProvider(),
                metadata.model(),
                ThreadStatus.NOT_LOADED,
                List.of(),
                threadDirectory.toAbsolutePath().normalize().toString(),
                metadata.cwd(),
                metadata.source(),
                true,
                metadata.archivedAt(),
                metadata.agentNickname(),
                metadata.agentRole(),
                metadata.agentPath(),
                metadata.parentThreadId() == null ? null : new ThreadId(metadata.parentThreadId()),
                metadata.agentDepth(),
                agentStatus(metadata),
                metadata.agentClosedAt());
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
        writeJson(threadMetadataFile(threadId), metadata.withUpdatedAt(updatedAt));
    }

    private String effectivePreview(ThreadId threadId, ThreadMetadata metadata) {
        if (metadata.preview() != null && !metadata.preview().isBlank()) {
            return metadata.preview();
        }
        List<ConversationTurn> turns = turns(threadId);
        if (!turns.isEmpty()) {
            return previewText(turns.get(0).userInput(), metadata.title());
        }
        return metadata.title();
    }

    private String defaultCwd() {
        return Path.of("").toAbsolutePath().normalize().toString();
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

    private AgentStatus agentStatus(ThreadMetadata metadata) {
        if (metadata == null || (metadata.parentThreadId() == null && metadata.agentPath() == null && metadata.agentNickname() == null)) {
            return null;
        }
        if (metadata.agentClosedAt() != null) {
            return AgentStatus.SHUTDOWN;
        }
        return AgentStatus.IDLE;
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

    private TurnStatus normalizeForkedStatus(TurnStatus status) {
        if (status == TurnStatus.RUNNING || status == TurnStatus.AWAITING_APPROVAL) {
            return TurnStatus.INTERRUPTED;
        }
        return status;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ThreadMetadata(String threadId,
                                  String title,
                                  Instant createdAt,
                                  Instant updatedAt,
                                  String preview,
                                  boolean ephemeral,
                                  String modelProvider,
                                  String model,
                                  String cwd,
                                  ThreadSource source,
                                  Instant archivedAt,
                                  String parentThreadId,
                                  Integer agentDepth,
                                  Instant agentClosedAt,
                                  String agentNickname,
                                  String agentRole,
                                  String agentPath) {

        private ThreadMetadata {
            title = title == null || title.isBlank() ? "New thread" : title.trim();
            preview = preview == null || preview.isBlank() ? null : preview.trim();
            cwd = cwd == null || cwd.isBlank() ? Path.of("").toAbsolutePath().normalize().toString() : cwd;
            source = source == null ? ThreadSource.UNKNOWN : source;
        }

        private static ThreadMetadata create(ThreadId threadId, String title, Instant now, String cwd) {
            return new ThreadMetadata(
                    threadId.value(),
                    title,
                    now,
                    now,
                    title,
                    false,
                    null,
                    null,
                    cwd,
                    ThreadSource.UNKNOWN,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        private ThreadMetadata forkedCopy(ThreadId forkedThreadId,
                                          String titleOverride,
                                          Boolean ephemeralOverride,
                                          String cwdOverride,
                                          String modelProviderOverride,
                                          String modelOverride,
                                          ThreadSource sourceOverride,
                                          String agentNicknameOverride,
                                          String agentRoleOverride,
                                          String agentPathOverride,
                                          Instant now) {
            return new ThreadMetadata(
                    forkedThreadId.value(),
                    titleOverride == null || titleOverride.isBlank() ? title : titleOverride,
                    now,
                    now,
                    preview,
                    ephemeralOverride == null ? ephemeral : ephemeralOverride,
                    modelProviderOverride == null ? modelProvider : modelProviderOverride,
                    modelOverride == null ? model : modelOverride,
                    cwdOverride == null || cwdOverride.isBlank() ? cwd : cwdOverride,
                    sourceOverride == null ? source : sourceOverride,
                    null,
                    null,
                    null,
                    null,
                    agentNicknameOverride == null ? agentNickname : agentNicknameOverride,
                    agentRoleOverride == null ? agentRole : agentRoleOverride,
                    agentPathOverride == null ? agentPath : agentPathOverride);
        }

        private ThreadMetadata withUpdatedAt(Instant newUpdatedAt) {
            return new ThreadMetadata(
                    threadId,
                    title,
                    createdAt,
                    newUpdatedAt == null ? Instant.now() : newUpdatedAt,
                    preview,
                    ephemeral,
                    modelProvider,
                    model,
                    cwd,
                    source,
                    archivedAt,
                    parentThreadId,
                    agentDepth,
                    agentClosedAt,
                    agentNickname,
                    agentRole,
                    agentPath);
        }

        private ThreadMetadata withArchivedAt(Instant newArchivedAt) {
            return new ThreadMetadata(
                    threadId,
                    title,
                    createdAt,
                    updatedAt,
                    preview,
                    ephemeral,
                    modelProvider,
                    model,
                    cwd,
                    source,
                    newArchivedAt,
                    parentThreadId,
                    agentDepth,
                    agentClosedAt,
                    agentNickname,
                    agentRole,
                    agentPath);
        }

        private ThreadMetadata withPreview(String newPreview) {
            return new ThreadMetadata(
                    threadId,
                    title,
                    createdAt,
                    updatedAt,
                    newPreview,
                    ephemeral,
                    modelProvider,
                    model,
                    cwd,
                    source,
                    archivedAt,
                    parentThreadId,
                    agentDepth,
                    agentClosedAt,
                    agentNickname,
                    agentRole,
                    agentPath);
        }

        private ThreadMetadata withParentThreadId(ThreadId newParentThreadId) {
            return new ThreadMetadata(
                    threadId,
                    title,
                    createdAt,
                    updatedAt,
                    preview,
                    ephemeral,
                    modelProvider,
                    model,
                    cwd,
                    source,
                    archivedAt,
                    newParentThreadId == null ? null : newParentThreadId.value(),
                    agentDepth,
                    agentClosedAt,
                    agentNickname,
                    agentRole,
                    agentPath);
        }

        private ThreadMetadata withAgentDepth(Integer newAgentDepth) {
            return new ThreadMetadata(
                    threadId,
                    title,
                    createdAt,
                    updatedAt,
                    preview,
                    ephemeral,
                    modelProvider,
                    model,
                    cwd,
                    source,
                    archivedAt,
                    parentThreadId,
                    newAgentDepth,
                    agentClosedAt,
                    agentNickname,
                    agentRole,
                    agentPath);
        }

        private ThreadMetadata withAgentClosedAt(Instant newAgentClosedAt) {
            return new ThreadMetadata(
                    threadId,
                    title,
                    createdAt,
                    updatedAt,
                    preview,
                    ephemeral,
                    modelProvider,
                    model,
                    cwd,
                    source,
                    archivedAt,
                    parentThreadId,
                    agentDepth,
                    newAgentClosedAt,
                    agentNickname,
                    agentRole,
                    agentPath);
        }

        private ThreadMetadata withAgentFields(String newAgentNickname, String newAgentRole, String newAgentPath) {
            return new ThreadMetadata(
                    threadId,
                    title,
                    createdAt,
                    updatedAt,
                    preview,
                    ephemeral,
                    modelProvider,
                    model,
                    cwd,
                    source,
                    archivedAt,
                    parentThreadId,
                    agentDepth,
                    agentClosedAt,
                    normalizeAgentField(newAgentNickname),
                    normalizeAgentField(newAgentRole),
                    normalizeAgentField(newAgentPath));
        }

        private static String normalizeAgentField(String value) {
            String normalized = value == null ? "" : value.trim();
            return normalized.isEmpty() ? null : normalized;
        }
    }
}
