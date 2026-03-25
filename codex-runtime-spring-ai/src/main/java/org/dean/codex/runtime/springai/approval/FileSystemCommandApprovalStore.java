package org.dean.codex.runtime.springai.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dean.codex.core.approval.CommandApprovalStore;
import org.dean.codex.protocol.approval.ApprovalId;
import org.dean.codex.protocol.approval.CommandApprovalRequest;
import org.dean.codex.protocol.conversation.ThreadId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class FileSystemCommandApprovalStore implements CommandApprovalStore {

    private static final String APPROVALS_DIRECTORY = "approvals";

    private final ObjectMapper objectMapper;
    private final Path approvalsRoot;

    public FileSystemCommandApprovalStore(Path storageRoot) {
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.approvalsRoot = storageRoot.toAbsolutePath().normalize().resolve(APPROVALS_DIRECTORY);
        try {
            Files.createDirectories(this.approvalsRoot);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to initialize approval storage at " + this.approvalsRoot, exception);
        }
    }

    @Override
    public synchronized void save(CommandApprovalRequest request) {
        if (request == null || request.approvalId() == null || request.approvalId().value() == null || request.approvalId().value().isBlank()) {
            throw new IllegalArgumentException("Approval request id must not be blank.");
        }

        try {
            writeJson(file(request.approvalId()), request);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to save approval request " + request.approvalId().value(), exception);
        }
    }

    @Override
    public synchronized Optional<CommandApprovalRequest> find(ApprovalId approvalId) {
        if (approvalId == null || approvalId.value() == null || approvalId.value().isBlank()) {
            return Optional.empty();
        }
        Path path = file(approvalId);
        return Files.exists(path) ? Optional.of(read(path)) : Optional.empty();
    }

    @Override
    public synchronized List<CommandApprovalRequest> list(ThreadId threadId) {
        if (threadId == null) {
            return List.of();
        }

        try (Stream<Path> stream = Files.list(approvalsRoot)) {
            return stream
                    .filter(path -> path.toString().endsWith(".json"))
                    .map(this::read)
                    .filter(request -> request.threadId().equals(threadId))
                    .sorted(Comparator.comparing(CommandApprovalRequest::createdAt).reversed())
                    .toList();
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to list approval requests for thread " + threadId.value(), exception);
        }
    }

    private CommandApprovalRequest read(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), CommandApprovalRequest.class);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to read approval request file " + path, exception);
        }
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

    private Path file(ApprovalId approvalId) {
        return approvalsRoot.resolve(approvalId.value() + ".json");
    }
}
