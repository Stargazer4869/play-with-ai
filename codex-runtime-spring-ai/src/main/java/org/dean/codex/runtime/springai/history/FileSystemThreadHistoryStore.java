package org.dean.codex.runtime.springai.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.core.history.ThreadHistoryStore;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.history.ThreadHistoryItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class FileSystemThreadHistoryStore implements ThreadHistoryStore {

    private static final String THREADS_DIRECTORY = "threads";
    private static final String THREAD_HISTORY_FILE = "thread-history.json";

    private final ConversationStore conversationStore;
    private final ObjectMapper objectMapper;
    private final com.fasterxml.jackson.databind.JavaType historyListType;
    private final Path storageRoot;

    public FileSystemThreadHistoryStore(ConversationStore conversationStore, Path storageRoot) {
        this.conversationStore = conversationStore;
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.historyListType = this.objectMapper.getTypeFactory().constructCollectionType(List.class, ThreadHistoryItem.class);
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
    }

    @Override
    public synchronized void append(ThreadId threadId, List<ThreadHistoryItem> items) {
        requireThread(threadId);
        if (items == null || items.isEmpty()) {
            return;
        }
        List<ThreadHistoryItem> history = new ArrayList<>(read(threadId));
        history.addAll(List.copyOf(items));
        write(threadHistoryFile(threadId), history);
    }

    @Override
    public synchronized List<ThreadHistoryItem> read(ThreadId threadId) {
        requireThread(threadId);
        Path path = threadHistoryFile(threadId);
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            return List.copyOf(objectMapper.readValue(path.toFile(), historyListType));
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to read thread history for " + threadId.value(), exception);
        }
    }

    @Override
    public synchronized void replace(ThreadId threadId, List<ThreadHistoryItem> items) {
        requireThread(threadId);
        write(threadHistoryFile(threadId), items == null ? List.of() : List.copyOf(items));
    }

    private void write(Path path, List<ThreadHistoryItem> items) {
        try {
            Files.createDirectories(path.getParent());
            Path tempFile = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
            try {
                objectMapper.writerFor(historyListType)
                        .withDefaultPrettyPrinter()
                        .writeValue(tempFile.toFile(), items == null ? List.of() : items);
                Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            finally {
                Files.deleteIfExists(tempFile);
            }
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to write thread history file " + path, exception);
        }
    }

    private void requireThread(ThreadId threadId) {
        if (threadId == null || !conversationStore.exists(threadId)) {
            throw new IllegalArgumentException("Unknown thread id: " + (threadId == null ? "<null>" : threadId.value()));
        }
    }

    private Path threadHistoryFile(ThreadId threadId) {
        return storageRoot.resolve(THREADS_DIRECTORY)
                .resolve(threadId.value())
                .resolve(THREAD_HISTORY_FILE);
    }
}
