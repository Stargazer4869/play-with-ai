package org.dean.codex.runtime.springai.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dean.codex.core.context.ContextManager;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.core.history.ThreadHistoryStore;
import org.dean.codex.protocol.context.ThreadMemory;
import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.MessageRole;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.history.CompactedHistoryItem;
import org.dean.codex.protocol.history.CompactionStrategy;
import org.dean.codex.protocol.history.HistoryCompactionSummaryItem;
import org.dean.codex.protocol.history.HistoryMessageItem;
import org.dean.codex.protocol.history.ThreadHistoryItem;
import org.dean.codex.runtime.springai.history.ThreadHistoryReplay;
import org.dean.codex.runtime.springai.history.ThreadHistoryMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class FileSystemContextManager implements ContextManager {

    private static final String THREADS_DIRECTORY = "threads";
    private static final String THREAD_MEMORY_FILE = "thread-memory.json";

    private final ConversationStore conversationStore;
    private final ThreadHistoryStore threadHistoryStore;
    private final ThreadCompactionSummarizer threadCompactionSummarizer;
    private final Path storageRoot;
    private final ObjectMapper objectMapper;
    private final int preserveRecentTurns;

    public FileSystemContextManager(ConversationStore conversationStore,
                                    ThreadHistoryStore threadHistoryStore,
                                    ThreadCompactionSummarizer threadCompactionSummarizer,
                                    Path storageRoot,
                                    int preserveRecentTurns) {
        this.conversationStore = conversationStore;
        this.threadHistoryStore = threadHistoryStore;
        this.threadCompactionSummarizer = threadCompactionSummarizer;
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
        List<ConversationTurn> allTurns = conversationStore.turns(threadId);
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

        Set<TurnId> compactedTurnIds = compactedTurns.stream()
                .map(ConversationTurn::turnId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<ConversationTurn> retainedTurns = allTurns.stream()
                .filter(turn -> !compactedTurnIds.contains(turn.turnId()))
                .toList();
        List<ThreadHistoryItem> visibleHistory = ThreadHistoryReplay.replayVisibleHistory(threadHistoryStore.read(threadId));
        if (visibleHistory.isEmpty()) {
            visibleHistory = historyForTurns(allTurns);
        }
        List<ThreadHistoryItem> compactedHistory = new ArrayList<>();
        List<ThreadHistoryItem> retainedHistory = new ArrayList<>();
        for (ThreadHistoryItem item : visibleHistory) {
            ConversationTurn turn = turnForHistoryItem(allTurns, item);
            if (item instanceof HistoryCompactionSummaryItem) {
                compactedHistory.add(item);
            }
            else if (turn != null && compactedTurnIds.contains(turn.turnId())) {
                compactedHistory.add(item);
            }
            else {
                retainedHistory.add(item);
            }
        }
        if (compactedHistory.isEmpty() && !compactedTurns.isEmpty()) {
            compactedHistory.addAll(historyForTurns(compactedTurns));
        }
        if (retainedHistory.isEmpty() && !retainedTurns.isEmpty()) {
            retainedHistory.addAll(historyForTurns(retainedTurns));
        }

        Instant compactedAt = Instant.now();
        String summaryText = summarizeCompactedHistory(threadId, compactedHistory, retainedHistory, compactedTurns);
        ThreadHistoryItem handoffItem = new HistoryCompactionSummaryItem(
                retainedTurns.get(0).turnId(),
                summaryText,
                compactedAt);
        List<ThreadHistoryItem> replacementHistory = new ArrayList<>();
        replacementHistory.add(handoffItem);
        replacementHistory.addAll(retainedHistory);
        CompactedHistoryItem compactedHistoryItem = new CompactedHistoryItem(
                summaryText,
                replacementHistory,
                compactedAt,
                CompactionStrategy.LOCAL_SUMMARY);
        threadHistoryStore.append(threadId, List.of(compactedHistoryItem));

        ThreadMemory threadMemory = new ThreadMemory(
                UUID.randomUUID().toString(),
                threadId,
                summaryText,
                compactedTurns.stream().map(ConversationTurn::turnId).toList(),
                compactedTurns.size(),
                compactedAt);
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

    private String summarizeCompactedHistory(ThreadId threadId,
                                             List<ThreadHistoryItem> compactedHistory,
                                             List<ThreadHistoryItem> retainedHistory,
                                             List<ConversationTurn> compactedTurns) {
        try {
            String summary = threadCompactionSummarizer.summarize(threadId, compactedHistory, retainedHistory);
            if (summary != null && !summary.isBlank()) {
                return summary.trim();
            }
        }
        catch (Exception exception) {
            // Fall back to a deterministic summary if the model-backed summarizer fails.
        }
        return summarizeTurns(compactedTurns);
    }

    private List<ThreadHistoryItem> historyForTurns(List<ConversationTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        List<ThreadHistoryItem> history = new ArrayList<>();
        for (ConversationTurn turn : turns) {
            List<ThreadHistoryItem> mappedItems = ThreadHistoryMapper.map(turn.turnId(), turn.items());
            boolean hasUserMessage = mappedItems.stream()
                    .anyMatch(item -> item instanceof HistoryMessageItem historyMessageItem
                            && historyMessageItem.role() == MessageRole.USER);
            boolean hasAssistantMessage = mappedItems.stream()
                    .anyMatch(item -> item instanceof HistoryMessageItem historyMessageItem
                            && historyMessageItem.role() == MessageRole.ASSISTANT);
            if (!hasUserMessage) {
                history.add(new HistoryMessageItem(turn.turnId(), MessageRole.USER, turn.userInput(), turn.startedAt()));
            }
            history.addAll(mappedItems);
            if (!hasAssistantMessage && turn.finalAnswer() != null && !turn.finalAnswer().isBlank()) {
                history.add(new HistoryMessageItem(
                        turn.turnId(),
                        MessageRole.ASSISTANT,
                        turn.finalAnswer(),
                        turn.completedAt() == null ? turn.startedAt() : turn.completedAt()));
            }
        }
        return List.copyOf(history);
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

    private ConversationTurn turnForHistoryItem(List<ConversationTurn> turns, ThreadHistoryItem item) {
        if (turns == null || turns.isEmpty()) {
            return null;
        }
        if (item instanceof HistoryCompactionSummaryItem summaryItem) {
            return turnForId(turns, summaryItem.anchorTurnId());
        }
        if (item.turnId() != null && item.turnId().value() != null && !item.turnId().value().isBlank()) {
            ConversationTurn turn = turnForId(turns, item.turnId());
            if (turn != null) {
                return turn;
            }
        }
        return turnForItem(turns, item.createdAt());
    }

    private ConversationTurn turnForItem(List<ConversationTurn> turns, Instant itemTimestamp) {
        if (turns == null || turns.isEmpty()) {
            return null;
        }
        ConversationTurn selected = turns.get(0);
        for (ConversationTurn turn : turns) {
            if (turn.startedAt() == null) {
                continue;
            }
            if (!turn.startedAt().isAfter(itemTimestamp)) {
                selected = turn;
                continue;
            }
            return selected;
        }
        return selected;
    }

    private ConversationTurn turnForId(List<ConversationTurn> turns, TurnId turnId) {
        if (turns == null || turns.isEmpty() || turnId == null) {
            return null;
        }
        for (ConversationTurn turn : turns) {
            if (turn.turnId().equals(turnId)) {
                return turn;
            }
        }
        return null;
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
