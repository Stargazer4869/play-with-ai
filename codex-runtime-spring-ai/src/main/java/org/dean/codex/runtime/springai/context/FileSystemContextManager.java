package org.dean.codex.runtime.springai.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dean.codex.core.context.ContextManager;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.protocol.context.ThreadMemory;
import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FileSystemContextManager implements ContextManager {

    private static final String THREADS_DIRECTORY = "threads";
    private static final String THREAD_MEMORY_FILE = "thread-memory.json";

    private final ConversationStore conversationStore;
    private final Path storageRoot;
    private final ObjectMapper objectMapper;
    private final int preserveRecentTurns;

    public FileSystemContextManager(ConversationStore conversationStore, Path storageRoot, int preserveRecentTurns) {
        this.conversationStore = conversationStore;
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.preserveRecentTurns = Math.max(1, preserveRecentTurns);
    }

    @Override
    public synchronized Optional<ThreadMemory> latestThreadMemory(ThreadId threadId) {
        if (threadId == null || !conversationStore.exists(threadId)) {
            return Optional.empty();
        }
        Path memoryFile = threadMemoryFile(threadId);
        if (!Files.exists(memoryFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(memoryFile.toFile(), ThreadMemory.class));
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to read thread memory for " + threadId.value(), exception);
        }
    }

    @Override
    public synchronized ThreadMemory compactThread(ThreadId threadId) {
        requireThread(threadId);
        List<ConversationTurn> eligibleTurns = conversationStore.turns(threadId).stream()
                .filter(turn -> turn.status() != TurnStatus.RUNNING && turn.status() != TurnStatus.AWAITING_APPROVAL)
                .toList();

        int compactCount = Math.max(0, eligibleTurns.size() - preserveRecentTurns);
        List<ConversationTurn> compactedTurns = compactCount > 0
                ? eligibleTurns.subList(0, compactCount)
                : List.of();

        if (compactedTurns.isEmpty()) {
            return latestThreadMemory(threadId).orElseGet(() -> persistThreadMemory(new ThreadMemory(
                    UUID.randomUUID().toString(),
                    threadId,
                    "No older completed turns are ready for compaction yet.",
                    List.of(),
                    0,
                    Instant.now())));
        }

        ThreadMemory threadMemory = new ThreadMemory(
                UUID.randomUUID().toString(),
                threadId,
                summarizeTurns(compactedTurns),
                compactedTurns.stream().map(ConversationTurn::turnId).toList(),
                compactedTurns.size(),
                Instant.now());
        return persistThreadMemory(threadMemory);
    }

    private ThreadMemory persistThreadMemory(ThreadMemory threadMemory) {
        try {
            writeJson(threadMemoryFile(threadMemory.threadId()), threadMemory);
            return threadMemory;
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to persist thread memory for " + threadMemory.threadId().value(), exception);
        }
    }

    private String summarizeTurns(List<ConversationTurn> turns) {
        StringBuilder summary = new StringBuilder("Compacted earlier thread context:")
                .append(System.lineSeparator());
        for (ConversationTurn turn : turns) {
            summary.append("- USER: ").append(abbreviate(turn.userInput())).append(System.lineSeparator());
            if (turn.finalAnswer() != null && !turn.finalAnswer().isBlank()) {
                summary.append("  ASSISTANT: ").append(abbreviate(turn.finalAnswer())).append(System.lineSeparator());
            }
            else {
                summary.append("  STATUS: ").append(turn.status()).append(System.lineSeparator());
            }
        }
        return summary.toString().trim();
    }

    private String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "(empty)";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 180 ? normalized : normalized.substring(0, 177) + "...";
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

    private void requireThread(ThreadId threadId) {
        if (threadId == null || !conversationStore.exists(threadId)) {
            throw new IllegalArgumentException("Unknown thread id: " + (threadId == null ? "<null>" : threadId.value()));
        }
    }

    private Path threadMemoryFile(ThreadId threadId) {
        return storageRoot.resolve(THREADS_DIRECTORY)
                .resolve(threadId.value())
                .resolve(THREAD_MEMORY_FILE);
    }
}
