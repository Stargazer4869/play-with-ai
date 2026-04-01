package org.dean.codex.cli;

import jakarta.annotation.PreDestroy;
import org.dean.codex.core.approval.CommandApprovalService;
import org.dean.codex.core.appserver.CodexAppServer;
import org.dean.codex.core.appserver.CodexAppServerSession;
import org.dean.codex.protocol.appserver.AppServerCapabilities;
import org.dean.codex.protocol.appserver.AppServerClientInfo;
import org.dean.codex.protocol.appserver.AppServerNotification;
import org.dean.codex.protocol.appserver.InitializeParams;
import org.dean.codex.protocol.appserver.InitializedNotification;
import org.dean.codex.protocol.appserver.SkillsListParams;
import org.dean.codex.protocol.appserver.ThreadArchiveParams;
import org.dean.codex.protocol.appserver.ThreadCompaction;
import org.dean.codex.protocol.appserver.ThreadCompactStartParams;
import org.dean.codex.protocol.appserver.ThreadCompactionStartedNotification;
import org.dean.codex.protocol.appserver.ThreadCompactedNotification;
import org.dean.codex.protocol.appserver.ThreadForkParams;
import org.dean.codex.protocol.appserver.ThreadListParams;
import org.dean.codex.protocol.appserver.ThreadListResponse;
import org.dean.codex.protocol.appserver.ThreadLoadedListParams;
import org.dean.codex.protocol.appserver.ThreadReadParams;
import org.dean.codex.protocol.appserver.ThreadReadResponse;
import org.dean.codex.protocol.appserver.ThreadRollbackParams;
import org.dean.codex.protocol.appserver.ThreadResumeParams;
import org.dean.codex.protocol.appserver.ThreadStartParams;
import org.dean.codex.protocol.appserver.ThreadUnarchiveParams;
import org.dean.codex.protocol.context.ThreadMemory;
import org.dean.codex.protocol.appserver.TurnCompletedNotification;
import org.dean.codex.protocol.appserver.TurnInterruptParams;
import org.dean.codex.protocol.appserver.TurnItemNotification;
import org.dean.codex.protocol.appserver.TurnResumeParams;
import org.dean.codex.protocol.appserver.TurnStartParams;
import org.dean.codex.protocol.appserver.TurnStartedNotification;
import org.dean.codex.protocol.appserver.TurnSteerParams;
import org.dean.codex.protocol.appserver.TurnSteerResponse;
import org.dean.codex.protocol.approval.ApprovalStatus;
import org.dean.codex.protocol.approval.CommandApprovalRequest;
import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.ThreadActiveFlag;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.ThreadSource;
import org.dean.codex.protocol.conversation.ThreadSummary;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.event.TurnEvent;
import org.dean.codex.protocol.item.AgentMessageItem;
import org.dean.codex.protocol.item.ApprovalItem;
import org.dean.codex.protocol.item.PlanItem;
import org.dean.codex.protocol.item.RuntimeErrorItem;
import org.dean.codex.protocol.item.ToolCallItem;
import org.dean.codex.protocol.item.ToolResultItem;
import org.dean.codex.protocol.item.TurnItem;
import org.dean.codex.protocol.item.UserMessageItem;
import org.dean.codex.protocol.runtime.RuntimeTurn;
import org.dean.codex.protocol.skill.SkillMetadata;
import org.dean.codex.protocol.tool.ShellCommandResult;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "codex.cli.enabled", havingValue = "true", matchIfMissing = true)
public class CodexConsoleRunner implements CommandLineRunner {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final CodexAppServerSession appServerSession;
    private final CommandApprovalService commandApprovalService;
    private ThreadId activeThreadId;
    private int threadSequence;

    @Autowired
    public CodexConsoleRunner(CodexAppServer codexAppServer,
                              CommandApprovalService commandApprovalService) {
        this.appServerSession = initializeSession(codexAppServer);
        this.commandApprovalService = commandApprovalService;
        List<ThreadSummary> threads = appServerSession.threadList().threads();
        this.threadSequence = threads.size() + 1;
        this.activeThreadId = threads.isEmpty() ? createThread("Thread " + threadSequence++) : threads.get(0).threadId();
    }

    @Override
    public void run(String @NonNull ... args) {
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.printf("Codex CLI. Active thread: %s%n", shortThreadId(activeThreadId));
            printHelp();
            while (true) {
                System.out.print("> ");
                if (!scanner.hasNextLine()) {
                    System.out.println("\nInput closed. Shutting down.");
                    return;
                }

                String input = scanner.nextLine().trim();
                if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                    System.out.println("Bye.");
                    return;
                }
                if (input.isEmpty()) {
                    continue;
                }
                if (handleConsoleCommand(input)) {
                    continue;
                }

                waitForTurn(() -> appServerSession.turnStart(new TurnStartParams(activeThreadId, input)).turn());
            }
        }
    }

    @PreDestroy
    void shutdown() throws Exception {
        appServerSession.close();
    }

    boolean handleConsoleCommand(String input) {
        if (input.equalsIgnoreCase(":help")) {
            printHelp();
            return true;
        }
        if (input.equalsIgnoreCase(":new")) {
            activeThreadId = createThread("Thread " + threadSequence++);
            System.out.println("Started new thread: " + shortThreadId(activeThreadId));
            return true;
        }
        if (isCommand(input, ":threads")) {
            String requestedMode = commandArguments(input, ":threads");
            printThreads(requestedMode);
            return true;
        }
        if (input.equalsIgnoreCase(":skills")) {
            printSkills();
            return true;
        }
        if (isCommand(input, ":resume") || isCommand(input, ":use")) {
            String command = isCommand(input, ":resume") ? ":resume" : ":use";
            String requestedThread = commandArguments(input, command);
            if (requestedThread.isEmpty()) {
                System.out.printf("Usage: %s <thread-id-prefix>%n", command);
                return true;
            }
            switchActiveThread(requestedThread);
            return true;
        }
        if (isCommand(input, ":fork")) {
            handleForkCommand(commandArguments(input, ":fork"));
            return true;
        }
        if (isCommand(input, ":archive")) {
            handleArchiveCommand(commandArguments(input, ":archive"));
            return true;
        }
        if (isCommand(input, ":unarchive")) {
            handleUnarchiveCommand(commandArguments(input, ":unarchive"));
            return true;
        }
        if (isCommand(input, ":rollback")) {
            handleRollbackCommand(commandArguments(input, ":rollback"));
            return true;
        }
        if (isCommand(input, ":subagents")) {
            printSubagents(commandArguments(input, ":subagents"));
            return true;
        }
        if (isCommand(input, ":agent")) {
            handleAgentCommand(commandArguments(input, ":agent"));
            return true;
        }
        if (input.equalsIgnoreCase(":approvals")) {
            printApprovals();
            return true;
        }
        if (input.equalsIgnoreCase(":history")) {
            printHistory();
            return true;
        }
        if (input.equalsIgnoreCase(":compact")) {
            compactThread();
            return true;
        }
        if (input.equalsIgnoreCase(":interrupt")) {
            ConversationTurn activeTurn = latestActiveTurn();
            if (activeTurn == null) {
                System.out.println("No active running turn in the current thread.");
                return true;
            }
            boolean accepted = appServerSession.turnInterrupt(new TurnInterruptParams(activeThreadId, activeTurn.turnId())).accepted();
            System.out.println(accepted ? "Interrupt requested." : "Interrupt request was not accepted.");
            return true;
        }
        if (input.startsWith(":steer")) {
            String steerInput = input.substring(6).trim();
            if (steerInput.isEmpty()) {
                System.out.println("Usage: :steer <message>");
                return true;
            }
            ConversationTurn activeTurn = latestActiveTurn();
            if (activeTurn == null) {
                System.out.println("No active running turn in the current thread.");
                return true;
            }
            TurnSteerResponse response = appServerSession.turnSteer(new TurnSteerParams(activeThreadId, activeTurn.turnId(), steerInput));
            System.out.println(response.accepted()
                    ? "Steering accepted for turn " + shortTurnId(response.turnId()) + "."
                    : "Steering request was not accepted.");
            return true;
        }
        if (input.startsWith(":approve")) {
            String approvalId = input.substring(8).trim();
            if (approvalId.isEmpty()) {
                System.out.println("Usage: :approve <approval-id-prefix>");
                return true;
            }
            handleApprovalDecision(approvalId, true, "");
            return true;
        }
        if (input.startsWith(":reject")) {
            String remainder = input.substring(7).trim();
            if (remainder.isEmpty()) {
                System.out.println("Usage: :reject <approval-id-prefix> [reason]");
                return true;
            }
            String[] parts = remainder.split("\\s+", 2);
            handleApprovalDecision(parts[0], false, parts.length > 1 ? parts[1] : "");
            return true;
        }
        return false;
    }

    ThreadId getActiveThreadIdForTest() {
        return activeThreadId;
    }

    private CodexAppServerSession initializeSession(CodexAppServer codexAppServer) {
        CodexAppServerSession session = codexAppServer.connect();
        session.initialize(new InitializeParams(
                new AppServerClientInfo("codex-java-cli", "Codex Java CLI", "1.0-SNAPSHOT"),
                new AppServerCapabilities(false, List.of())));
        session.initialized(new InitializedNotification());
        return session;
    }

    private ThreadId createThread(String title) {
        return appServerSession.threadStart(new ThreadStartParams(title)).thread().threadId();
    }

    private void printHelp() {
        System.out.println("Commands: :help, :new, :threads [all|loaded|archived], :resume <thread-id-prefix>, :use <thread-id-prefix>, :fork [thread-id-prefix] [title], :archive [thread-id-prefix], :unarchive <thread-id-prefix>, :rollback [thread-id-prefix] <turn-count>, :subagents [thread-id-prefix], :agent <tree|use> ..., :skills, :history, :compact, :approvals, :approve <approval-id-prefix>, :reject <approval-id-prefix> [reason], :interrupt, :steer <message>, exit, quit");
    }

    private void printThreads(String requestedMode) {
        String mode = requestedMode == null ? "" : requestedMode.trim().toLowerCase();
        List<ThreadSummary> threads;
        switch (mode) {
            case "", "active" -> threads = fetchThreads(false);
            case "all" -> threads = fetchAllThreads();
            case "archived" -> threads = fetchThreads(true);
            case "loaded" -> {
                Set<String> loadedIds = fetchLoadedThreadIds().stream()
                        .map(ThreadId::value)
                        .collect(Collectors.toSet());
                threads = fetchAllThreads().stream()
                        .filter(thread -> loadedIds.contains(thread.threadId().value()))
                        .toList();
            }
            default -> {
                System.out.println("Usage: :threads [all|loaded|archived]");
                return;
            }
        }
        if (threads.isEmpty()) {
            String label = mode.isEmpty() ? "active" : mode;
            System.out.println("No " + label + " threads.");
            return;
        }
        for (ThreadSummary thread : threads) {
            printThreadSummary(thread);
        }
    }

    private void printThreadSummary(ThreadSummary thread) {
        String marker = thread.threadId().equals(activeThreadId) ? "*" : " ";
        System.out.printf("%s %s  %s  turns=%d  updated=%s%n",
                marker,
                shortThreadId(thread.threadId()),
                thread.title(),
                thread.turnCount(),
                formatTimestamp(thread.updatedAt()));

        List<String> details = new ArrayList<>();
        details.add("status=" + formatEnum(thread.status()));
        details.add("source=" + formatThreadSource(thread.source()));
        if (thread.archived()) {
            details.add("archived");
        }
        if (thread.parentThreadId() != null) {
            details.add("sub-agent");
        }
        if (!thread.activeFlags().isEmpty()) {
            details.add("flags=" + thread.activeFlags().stream()
                    .map(this::formatThreadFlag)
                    .collect(Collectors.joining(",")));
        }
        if (thread.agentStatus() != null) {
            details.add("agent=" + formatEnum(thread.agentStatus()));
        }
        System.out.println("  " + String.join("  ", details));

        if (thread.parentThreadId() != null
                || thread.agentNickname() != null
                || thread.agentRole() != null
                || thread.agentPath() != null
                || thread.agentDepth() != null) {
            List<String> agentDetails = new ArrayList<>();
            if (thread.parentThreadId() != null) {
                agentDetails.add("parent=" + shortThreadId(thread.parentThreadId()));
            }
            if (thread.agentNickname() != null) {
                agentDetails.add("nickname=" + thread.agentNickname());
            }
            if (thread.agentRole() != null) {
                agentDetails.add("role=" + thread.agentRole());
            }
            if (thread.agentDepth() != null) {
                agentDetails.add("depth=" + thread.agentDepth());
            }
            if (thread.agentPath() != null) {
                agentDetails.add("path=" + thread.agentPath());
            }
            if (thread.agentClosedAt() != null) {
                agentDetails.add("closedAt=" + formatTimestamp(thread.agentClosedAt()));
            }
            System.out.println("  agent: " + String.join("  ", agentDetails));
        }

        if (thread.cwd() != null && !thread.cwd().isBlank()) {
            System.out.println("  cwd: " + thread.cwd());
        }
        if (thread.preview() != null && !thread.preview().isBlank()) {
            System.out.println("  preview: " + thread.preview());
        }
    }

    private void printSkills() {
        List<SkillMetadata> skills = appServerSession.skillsList(new SkillsListParams(false)).skills();
        if (skills.isEmpty()) {
            System.out.println("No skills discovered. Add SKILL.md files under the configured user or workspace skills roots.");
            return;
        }
        for (SkillMetadata skill : skills) {
            System.out.printf("- %s [%s] %s%n",
                    skill.name(),
                    skill.scope(),
                    blankToPlaceholder(skill.shortDescription()));
            System.out.printf("  path: %s%n", skill.path());
            System.out.printf("  use: mention `$%s` in your request%n", skill.name());
        }
    }

    private void printHistory() {
        ThreadReadResponse response = appServerSession.threadRead(new ThreadReadParams(activeThreadId));
        List<ConversationTurn> turns = response.turns();
        if (turns.isEmpty()) {
            if (response.threadMemory() == null) {
                System.out.println("No turns in the active thread yet.");
                return;
            }
        }
        if (response.threadMemory() != null) {
            printThreadMemory(response.threadMemory());
        }
        for (ConversationTurn turn : turns) {
            System.out.printf("[%s] USER: %s%n", turn.status(), turn.userInput());
            if (!turn.items().isEmpty()) {
                for (TurnItem item : turn.items()) {
                    System.out.print("  ");
                    printItem(item);
                }
            }
            else {
                for (TurnEvent event : turn.events()) {
                    System.out.printf("  - %s: %s%n", event.type(), event.detail());
                }
            }
            if (shouldPrintFinalAnswer(turn)) {
                System.out.printf("ASSISTANT: %s%n", turn.finalAnswer());
            }
        }
    }

    private void compactThread() {
        try (CompactionNotificationSession session = new CompactionNotificationSession(activeThreadId)) {
            var response = appServerSession.threadCompactStart(new ThreadCompactStartParams(activeThreadId));
            session.attach(response.compaction());
            session.awaitCompletion();
            if (response.compaction() != null) {
                printCompactionResponse(response.compaction(), response.threadMemory());
            }
            else if (response.threadMemory() != null) {
                printThreadMemory(response.threadMemory());
            }
        }
        catch (Exception exception) {
            throw new IllegalStateException("Failed to compact the active thread.", exception);
        }
    }

    private void printThreadMemory(ThreadMemory threadMemory) {
        System.out.printf("[memory] %s turns compacted at %s%n",
                threadMemory.compactedTurnCount(),
                TIMESTAMP_FORMAT.format(threadMemory.createdAt()));
        if (threadMemory.summary() != null && !threadMemory.summary().isBlank()) {
            System.out.println(threadMemory.summary());
        }
    }

    private void printCompactionNotification(AppServerNotification notification) {
        if (notification instanceof ThreadCompactionStartedNotification started) {
            printCompactionStarted(started.compaction());
            return;
        }
        if (notification instanceof ThreadCompactedNotification completed) {
            printCompactionCompleted(completed.compaction());
        }
    }

    private void printCompactionStarted(ThreadCompaction compaction) {
        if (compaction == null) {
            return;
        }
        System.out.printf("[compaction] started %s on thread %s%n",
                shortCompactionId(compaction),
                shortThreadId(compaction.threadId()));
    }

    private void printCompactionCompleted(ThreadCompaction compaction) {
        if (compaction == null) {
            return;
        }
        System.out.printf("[compaction] completed %s from %d turns at %s%n",
                shortCompactionId(compaction),
                compaction.compactedTurnCount(),
                compaction.completedAt() == null ? "(unknown)" : TIMESTAMP_FORMAT.format(compaction.completedAt()));
        if (compaction.summary() != null && !compaction.summary().isBlank()) {
            System.out.println(compaction.summary());
        }
    }

    private void printCompactionResponse(ThreadCompaction compaction, ThreadMemory threadMemory) {
        System.out.printf("[compaction] response %s from %d turns%n",
                shortCompactionId(compaction),
                compaction.compactedTurnCount());
        if (threadMemory != null) {
            System.out.printf("[memory] compatibility snapshot %s at %s%n",
                    threadMemory.memoryId(),
                    TIMESTAMP_FORMAT.format(threadMemory.createdAt()));
        }
    }

    private void printItem(TurnItem item) {
        if (item instanceof UserMessageItem userMessageItem) {
            System.out.println("[user] " + userMessageItem.text());
            return;
        }
        if (item instanceof AgentMessageItem agentMessageItem) {
            System.out.println("[assistant] " + agentMessageItem.text());
            return;
        }
        if (item instanceof PlanItem planItem) {
            System.out.println("[plan] " + summarizePlan(planItem));
            return;
        }
        if (item instanceof ToolCallItem toolCallItem) {
            System.out.println("[tool] " + toolCallItem.toolName() + " " + blankToPlaceholder(toolCallItem.target()));
            return;
        }
        if (item instanceof ToolResultItem toolResultItem) {
            System.out.println("[tool-result] " + toolResultItem.toolName() + " " + toolResultItem.summary());
            return;
        }
        if (item instanceof ApprovalItem approvalItem) {
            System.out.println("[approval] " + approvalItem.state() + " " + approvalItem.detail());
            return;
        }
        if (item instanceof RuntimeErrorItem runtimeErrorItem) {
            System.out.println("[runtime-error] " + runtimeErrorItem.message());
            return;
        }
        System.out.println("[item] " + item.getClass().getSimpleName());
    }

    private void printApprovals() {
        List<CommandApprovalRequest> approvals = commandApprovalService.approvals(activeThreadId);
        if (approvals.isEmpty()) {
            System.out.println("No approval requests for the active thread.");
            return;
        }

        for (CommandApprovalRequest approval : approvals) {
            String marker = approval.status() == ApprovalStatus.PENDING ? "*" : " ";
            System.out.printf("%s %s  %s  %s%n",
                    marker,
                    shortApprovalId(approval),
                    approval.status(),
                    approval.command());
            if (approval.reason() != null && !approval.reason().isBlank()) {
                System.out.printf("  reason: %s%n", approval.reason());
            }
            if (approval.resolutionNote() != null && !approval.resolutionNote().isBlank()) {
                System.out.printf("  note: %s%n", approval.resolutionNote());
            }
        }
    }

    private void handleApprovalDecision(String approvalIdPrefix, boolean approve, String reason) {
        try {
            CommandApprovalRequest approval = approve
                    ? commandApprovalService.approve(activeThreadId, approvalIdPrefix)
                    : commandApprovalService.reject(activeThreadId, approvalIdPrefix, reason);
            if (approve) {
                System.out.println("Approved command " + shortApprovalId(approval) + ".");
                printApprovedCommandResult(approval.executionResult());
            }
            else {
                System.out.println("Rejected command " + shortApprovalId(approval) + ".");
            }
            waitForTurn(() -> appServerSession.turnResume(new TurnResumeParams(activeThreadId, approval.turnId())).turn());
        }
        catch (IllegalArgumentException exception) {
            System.out.println(exception.getMessage());
        }
    }

    private void printApprovedCommandResult(ShellCommandResult result) {
        if (result == null) {
            System.out.println("[approval] No execution result recorded.");
            return;
        }

        System.out.printf("[approval] success=%s exitCode=%d%n", result.success(), result.exitCode());
        if (result.stdout() != null && !result.stdout().isBlank()) {
            System.out.println("[stdout]");
            System.out.println(result.stdout());
        }
        if (result.stderr() != null && !result.stderr().isBlank()) {
            System.out.println("[stderr]");
            System.out.println(result.stderr());
        }
        if (result.error() != null && !result.error().isBlank()) {
            System.out.println("[approval-error] " + result.error());
        }
    }

    private String shortThreadId(ThreadId threadId) {
        String value = threadId.value();
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    private String formatTimestamp(java.time.Instant timestamp) {
        return timestamp == null ? "(unknown)" : TIMESTAMP_FORMAT.format(timestamp);
    }

    private String shortCompactionId(ThreadCompaction compaction) {
        String value = compaction.compactionId();
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    private String shortApprovalId(CommandApprovalRequest approval) {
        String value = approval.approvalId().value();
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    private String summarizePlan(PlanItem planItem) {
        if (planItem.plan() == null || planItem.plan().edits().isEmpty()) {
            return blankToPlaceholder(planItem.plan() == null ? "" : planItem.plan().summary());
        }
        String edits = planItem.plan().edits().stream()
                .map(edit -> edit.type() + " " + blankToPlaceholder(edit.path()) + ": " + blankToPlaceholder(edit.description()))
                .reduce((left, right) -> left + "; " + right)
                .orElse("(none)");
        return blankToPlaceholder(planItem.plan().summary()) + " | " + edits;
    }

    private String blankToPlaceholder(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }

    private boolean shouldPrintFinalAnswer(ConversationTurn turn) {
        if (turn.finalAnswer() == null || turn.finalAnswer().isBlank()) {
            return false;
        }
        return turn.items().stream()
                .filter(AgentMessageItem.class::isInstance)
                .map(AgentMessageItem.class::cast)
                .map(AgentMessageItem::text)
                .noneMatch(turn.finalAnswer()::equals);
    }

    private ConversationTurn latestActiveTurn() {
        return appServerSession.threadRead(new ThreadReadParams(activeThreadId)).turns().stream()
                .filter(turn -> turn.status() == org.dean.codex.protocol.conversation.TurnStatus.RUNNING
                        || turn.status() == org.dean.codex.protocol.conversation.TurnStatus.AWAITING_APPROVAL)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private boolean isCommand(String input, String command) {
        return input.equalsIgnoreCase(command)
                || (input.length() > command.length()
                && input.regionMatches(true, 0, command, 0, command.length())
                && Character.isWhitespace(input.charAt(command.length())));
    }

    private String commandArguments(String input, String command) {
        if (input.length() <= command.length()) {
            return "";
        }
        return input.substring(command.length()).trim();
    }

    private void switchActiveThread(String requestedThread) {
        ThreadSummary resolved = resolveRequiredThread(requestedThread, true);
        if (resolved == null) {
            return;
        }
        if (resolved.archived()) {
            System.out.println("Thread is archived. Unarchive it before resuming: " + shortThreadId(resolved.threadId()));
            return;
        }
        appServerSession.threadResume(new ThreadResumeParams(resolved.threadId()));
        activeThreadId = resolved.threadId();
        System.out.println("Switched to thread: " + shortThreadId(activeThreadId));
    }

    private void handleForkCommand(String arguments) {
        String remainder = arguments == null ? "" : arguments.trim();
        ThreadId sourceThreadId = activeThreadId;
        String title = null;
        if (!remainder.isEmpty()) {
            if (remainder.startsWith("--title ")) {
                title = blankToNull(remainder.substring("--title ".length()).trim());
                if (title == null) {
                    System.out.println("Usage: :fork [thread-id-prefix] [title] or :fork --title <title>");
                    return;
                }
            }
            else {
                String[] parts = remainder.split("\\s+", 2);
                List<ThreadSummary> matches = findMatchingThreads(parts[0], true);
                if (matches.size() == 1) {
                    sourceThreadId = matches.get(0).threadId();
                    title = parts.length > 1 ? blankToNull(parts[1]) : null;
                }
                else if (matches.size() > 1) {
                    printAmbiguousThreads(parts[0], matches);
                    return;
                }
                else {
                    title = blankToNull(remainder);
                }
            }
        }

        ThreadSummary forked = appServerSession.threadFork(new ThreadForkParams(
                sourceThreadId,
                title,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null)).thread();
        activeThreadId = forked.threadId();
        System.out.printf("Forked thread %s from %s.%n", shortThreadId(forked.threadId()), shortThreadId(sourceThreadId));
        System.out.println("Switched to thread: " + shortThreadId(activeThreadId));
    }

    private void handleArchiveCommand(String arguments) {
        ThreadSummary target = arguments == null || arguments.isBlank()
                ? findThreadById(activeThreadId)
                : resolveRequiredThread(arguments.trim(), true);
        if (target == null) {
            return;
        }
        ThreadSummary archived = appServerSession.threadArchive(new ThreadArchiveParams(target.threadId())).thread();
        System.out.println("Archived thread: " + shortThreadId(archived.threadId()));
        if (archived.threadId().equals(activeThreadId)) {
            switchAfterArchivingActiveThread(archived.threadId());
        }
    }

    private void handleUnarchiveCommand(String arguments) {
        String prefix = arguments == null ? "" : arguments.trim();
        if (prefix.isEmpty()) {
            System.out.println("Usage: :unarchive <thread-id-prefix>");
            return;
        }
        ThreadSummary target = resolveRequiredThread(prefix, true);
        if (target == null) {
            return;
        }
        ThreadSummary unarchived = appServerSession.threadUnarchive(new ThreadUnarchiveParams(target.threadId())).thread();
        System.out.println("Unarchived thread: " + shortThreadId(unarchived.threadId()));
    }

    private void handleRollbackCommand(String arguments) {
        String remainder = arguments == null ? "" : arguments.trim();
        if (remainder.isEmpty()) {
            System.out.println("Usage: :rollback [thread-id-prefix] <turn-count>");
            return;
        }
        String[] parts = remainder.split("\\s+");
        ThreadId targetThreadId;
        int turnCount;
        if (parts.length == 1) {
            targetThreadId = activeThreadId;
            turnCount = parseRollbackCount(parts[0]);
        }
        else if (parts.length == 2) {
            ThreadSummary target = resolveRequiredThread(parts[0], true);
            if (target == null) {
                return;
            }
            targetThreadId = target.threadId();
            turnCount = parseRollbackCount(parts[1]);
        }
        else {
            System.out.println("Usage: :rollback [thread-id-prefix] <turn-count>");
            return;
        }
        if (turnCount < 1) {
            return;
        }
        var response = appServerSession.threadRollback(new ThreadRollbackParams(targetThreadId, turnCount));
        System.out.printf("Rolled back %d turn(s) on thread %s. Remaining turns=%d%n",
                turnCount,
                shortThreadId(targetThreadId),
                response.thread().turnCount());
    }

    private int parseRollbackCount(String rawValue) {
        try {
            int parsed = Integer.parseInt(rawValue);
            if (parsed < 1) {
                System.out.println("Turn count must be >= 1.");
                return -1;
            }
            return parsed;
        }
        catch (NumberFormatException exception) {
            System.out.println("Invalid turn count: " + rawValue);
            return -1;
        }
    }

    private void printSubagents(String arguments) {
        ThreadSummary target = arguments == null || arguments.isBlank()
                ? findThreadById(activeThreadId)
                : resolveRequiredThread(arguments.trim(), true);
        if (target == null) {
            return;
        }
        ThreadReadResponse response = appServerSession.threadRead(new ThreadReadParams(target.threadId(), false));
        List<ThreadSummary> threadTree = new ArrayList<>();
        threadTree.add(response.thread());
        threadTree.addAll(response.relatedThreads());
        if (threadTree.size() == 1 && response.thread().parentThreadId() == null) {
            System.out.println("No related sub-agent threads for " + shortThreadId(response.thread().threadId()) + ".");
            return;
        }

        Map<ThreadId, ThreadSummary> threadsById = new LinkedHashMap<>();
        for (ThreadSummary thread : threadTree) {
            threadsById.put(thread.threadId(), thread);
        }
        Map<ThreadId, List<ThreadSummary>> childrenByParent = new LinkedHashMap<>();
        for (ThreadSummary thread : threadTree) {
            if (thread.parentThreadId() == null) {
                continue;
            }
            childrenByParent.computeIfAbsent(thread.parentThreadId(), ignored -> new ArrayList<>()).add(thread);
        }

        ThreadId rootThreadId = response.treeRootThreadId() == null ? response.thread().threadId() : response.treeRootThreadId();
        ThreadSummary root = threadsById.getOrDefault(rootThreadId, response.thread());
        System.out.printf("Thread tree rooted at %s:%n", shortThreadId(root.threadId()));
        printThreadTreeNode(root, childrenByParent, 0, response.thread().threadId());
    }

    private void printThreadTreeNode(ThreadSummary thread,
                                     Map<ThreadId, List<ThreadSummary>> childrenByParent,
                                     int depth,
                                     ThreadId focusedThreadId) {
        String indent = "  ".repeat(depth);
        String marker = thread.threadId().equals(activeThreadId)
                ? "*"
                : thread.threadId().equals(focusedThreadId) ? ">" : "-";
        List<String> tags = new ArrayList<>();
        tags.add(formatEnum(thread.status()));
        if (thread.parentThreadId() != null) {
            tags.add("sub-agent");
        }
        if (thread.agentStatus() != null) {
            tags.add("agent=" + formatEnum(thread.agentStatus()));
        }
        if (thread.archived()) {
            tags.add("archived");
        }
        System.out.printf("%s%s %s  %s  [%s]%n",
                indent,
                marker,
                shortThreadId(thread.threadId()),
                thread.title(),
                String.join(", ", tags));
        if (thread.agentNickname() != null || thread.agentRole() != null || thread.agentPath() != null) {
            List<String> agentDetails = new ArrayList<>();
            if (thread.agentNickname() != null) {
                agentDetails.add("nickname=" + thread.agentNickname());
            }
            if (thread.agentRole() != null) {
                agentDetails.add("role=" + thread.agentRole());
            }
            if (thread.agentPath() != null) {
                agentDetails.add("path=" + thread.agentPath());
            }
            System.out.printf("%s  agent: %s%n", indent, String.join("  ", agentDetails));
        }
        for (ThreadSummary child : childrenByParent.getOrDefault(thread.threadId(), List.of())) {
            printThreadTreeNode(child, childrenByParent, depth + 1, focusedThreadId);
        }
    }

    private void handleAgentCommand(String arguments) {
        String remainder = arguments == null ? "" : arguments.trim();
        if (remainder.isEmpty()) {
            System.out.println("Usage: :agent <tree|use> ...");
            return;
        }
        if (isCommand(remainder, "tree")) {
            printSubagents(commandArguments(remainder, "tree"));
            return;
        }
        if (isCommand(remainder, "use")) {
            String requestedThread = commandArguments(remainder, "use");
            if (requestedThread.isEmpty()) {
                System.out.println("Usage: :agent use <thread-id-prefix>");
                return;
            }
            switchActiveThread(requestedThread);
            return;
        }
        System.out.println("Usage: :agent <tree|use> ...");
    }

    private List<ThreadSummary> fetchThreads(Boolean archived) {
        List<ThreadSummary> threads = new ArrayList<>();
        String cursor = null;
        do {
            ThreadListResponse response = appServerSession.threadList(new ThreadListParams(
                    cursor,
                    100,
                    null,
                    null,
                    null,
                    archived,
                    null,
                    null));
            threads.addAll(response.threads());
            cursor = response.nextCursor();
        }
        while (cursor != null);
        return threads;
    }

    private List<ThreadSummary> fetchAllThreads() {
        Map<String, ThreadSummary> threadsById = new LinkedHashMap<>();
        for (ThreadSummary thread : fetchThreads(false)) {
            threadsById.put(thread.threadId().value(), thread);
        }
        for (ThreadSummary thread : fetchThreads(true)) {
            threadsById.put(thread.threadId().value(), thread);
        }
        return new ArrayList<>(threadsById.values());
    }

    private List<ThreadId> fetchLoadedThreadIds() {
        List<ThreadId> loaded = new ArrayList<>();
        String cursor = null;
        do {
            var response = appServerSession.threadLoadedList(new ThreadLoadedListParams(cursor, 100));
            loaded.addAll(response.data());
            cursor = response.nextCursor();
        }
        while (cursor != null);
        return loaded;
    }

    private ThreadSummary findThreadById(ThreadId threadId) {
        return fetchAllThreads().stream()
                .filter(thread -> thread.threadId().equals(threadId))
                .findFirst()
                .orElse(null);
    }

    private ThreadSummary resolveRequiredThread(String prefix, boolean includeArchived) {
        List<ThreadSummary> matches = findMatchingThreads(prefix, includeArchived);
        if (matches.isEmpty()) {
            System.out.println("No thread matched: " + prefix);
            return null;
        }
        if (matches.size() > 1) {
            printAmbiguousThreads(prefix, matches);
            return null;
        }
        return matches.get(0);
    }

    private List<ThreadSummary> findMatchingThreads(String prefix, boolean includeArchived) {
        List<ThreadSummary> threads = includeArchived ? fetchAllThreads() : fetchThreads(false);
        return threads.stream()
                .filter(thread -> thread.threadId().value().startsWith(prefix))
                .toList();
    }

    private void printAmbiguousThreads(String prefix, List<ThreadSummary> matches) {
        System.out.println("Multiple threads matched " + prefix + ":");
        for (ThreadSummary thread : matches) {
            System.out.printf("  %s  %s%n", shortThreadId(thread.threadId()), thread.title());
        }
    }

    private void switchAfterArchivingActiveThread(ThreadId archivedThreadId) {
        List<ThreadSummary> candidates = fetchThreads(false).stream()
                .filter(thread -> !thread.threadId().equals(archivedThreadId))
                .toList();
        if (candidates.isEmpty()) {
            activeThreadId = createThread("Thread " + threadSequence++);
            System.out.println("Started replacement thread: " + shortThreadId(activeThreadId));
            return;
        }
        ThreadSummary replacement = candidates.get(0);
        if (!replacement.loaded() && !replacement.archived()) {
            appServerSession.threadResume(new ThreadResumeParams(replacement.threadId()));
        }
        activeThreadId = replacement.threadId();
        System.out.println("Switched to thread: " + shortThreadId(activeThreadId));
    }

    private String formatEnum(Enum<?> value) {
        if (value == null) {
            return "unknown";
        }
        return value.name().toLowerCase().replace('_', '-');
    }

    private String formatThreadFlag(ThreadActiveFlag flag) {
        return formatEnum(flag);
    }

    private String formatThreadSource(ThreadSource source) {
        return formatEnum(source);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void waitForTurn(TurnStarter turnStarter) {
        try (TurnNotificationSession session = new TurnNotificationSession(activeThreadId)) {
            RuntimeTurn runtimeTurn = turnStarter.start();
            session.attach(runtimeTurn.turnId());
            session.awaitCompletion();
            ConversationTurn completedTurn = appServerSession.threadRead(new ThreadReadParams(activeThreadId)).turns().stream()
                    .filter(turn -> turn.turnId().equals(runtimeTurn.turnId()))
                    .findFirst()
                    .orElseThrow();
            if (shouldPrintFinalAnswer(completedTurn)) {
                System.out.println(completedTurn.finalAnswer());
            }
        }
        catch (IllegalArgumentException exception) {
            System.out.println(exception.getMessage());
        }
        catch (Exception exception) {
            throw new IllegalStateException("Failed while waiting for turn notifications.", exception);
        }
    }

    @FunctionalInterface
    private interface TurnStarter {
        RuntimeTurn start();
    }

    private final class TurnNotificationSession implements AutoCloseable {

        private final CountDownLatch completionLatch = new CountDownLatch(1);
        private final List<AppServerNotification> pendingNotifications = new ArrayList<>();
        private final AutoCloseable subscription;
        private TurnId targetTurnId;

        private TurnNotificationSession(ThreadId threadId) {
            try {
                this.subscription = appServerSession.subscribe(this::onNotification);
            }
            catch (Exception exception) {
                throw new IllegalStateException("Unable to subscribe to runtime notifications for thread "
                        + shortThreadId(threadId), exception);
            }
        }

        private synchronized void attach(TurnId turnId) {
            this.targetTurnId = turnId;
            for (AppServerNotification notification : pendingNotifications) {
                if (matchesTurn(notification, turnId)) {
                    process(notification);
                }
            }
            pendingNotifications.clear();
        }

        private void awaitCompletion() {
            try {
                completionLatch.await();
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for runtime notifications.", exception);
            }
        }

        private synchronized void onNotification(AppServerNotification notification) {
            if (targetTurnId == null) {
                pendingNotifications.add(notification);
                return;
            }
            if (!matchesTurn(notification, targetTurnId)) {
                return;
            }
            process(notification);
        }

        private boolean matchesTurn(AppServerNotification notification, TurnId turnId) {
            if (notification instanceof TurnStartedNotification started) {
                return started.turn() != null && turnId.equals(started.turn().turnId());
            }
            if (notification instanceof TurnItemNotification item) {
                return item.turn() != null && turnId.equals(item.turn().turnId());
            }
            if (notification instanceof TurnCompletedNotification completed) {
                return completed.turn() != null && turnId.equals(completed.turn().turnId());
            }
            return false;
        }

        private void process(AppServerNotification notification) {
            if (notification instanceof TurnItemNotification itemNotification && itemNotification.item() != null) {
                printItem(itemNotification.item());
            }
            if (notification instanceof TurnCompletedNotification) {
                completionLatch.countDown();
            }
        }

        @Override
        public void close() throws Exception {
            subscription.close();
        }
    }

    private final class CompactionNotificationSession implements AutoCloseable {

        private final CountDownLatch completionLatch = new CountDownLatch(1);
        private final List<AppServerNotification> pendingNotifications = new ArrayList<>();
        private final AutoCloseable subscription;
        private final ThreadId threadId;
        private String targetCompactionId;

        private CompactionNotificationSession(ThreadId threadId) {
            this.threadId = threadId;
            try {
                this.subscription = appServerSession.subscribe(this::onNotification);
            }
            catch (Exception exception) {
                throw new IllegalStateException("Unable to subscribe to compaction notifications for thread "
                        + shortThreadId(threadId), exception);
            }
        }

        private synchronized void attach(ThreadCompaction compaction) {
            if (compaction == null) {
                completionLatch.countDown();
                return;
            }
            this.targetCompactionId = compaction.compactionId();
            for (AppServerNotification notification : pendingNotifications) {
                if (matches(notification)) {
                    process(notification);
                }
            }
            pendingNotifications.clear();
        }

        private void awaitCompletion() {
            try {
                completionLatch.await(1, TimeUnit.SECONDS);
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for compaction notifications.", exception);
            }
        }

        private synchronized void onNotification(AppServerNotification notification) {
            if (targetCompactionId == null) {
                pendingNotifications.add(notification);
                return;
            }
            if (!matches(notification)) {
                return;
            }
            process(notification);
        }

        private boolean matches(AppServerNotification notification) {
            if (notification instanceof ThreadCompactionStartedNotification started) {
                return matches(started.compaction());
            }
            if (notification instanceof ThreadCompactedNotification completed) {
                return matches(completed.compaction());
            }
            return false;
        }

        private boolean matches(ThreadCompaction compaction) {
            return compaction != null
                    && compaction.threadId() != null
                    && compaction.threadId().equals(threadId)
                    && targetCompactionId != null
                    && targetCompactionId.equals(compaction.compactionId());
        }

        private void process(AppServerNotification notification) {
            printCompactionNotification(notification);
            if (notification instanceof ThreadCompactedNotification) {
                completionLatch.countDown();
            }
        }

        @Override
        public void close() throws Exception {
            subscription.close();
        }
    }

    private String shortTurnId(TurnId turnId) {
        String value = turnId.value();
        return value.length() <= 8 ? value : value.substring(0, 8);
    }
}
