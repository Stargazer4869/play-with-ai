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
import org.dean.codex.protocol.appserver.ThreadCompactStartParams;
import org.dean.codex.protocol.appserver.ThreadReadParams;
import org.dean.codex.protocol.appserver.ThreadReadResponse;
import org.dean.codex.protocol.appserver.ThreadResumeParams;
import org.dean.codex.protocol.appserver.ThreadStartParams;
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
import org.dean.codex.protocol.conversation.ThreadId;
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
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;

@Component
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
        if (input.equalsIgnoreCase(":threads")) {
            printThreads();
            return true;
        }
        if (input.equalsIgnoreCase(":skills")) {
            printSkills();
            return true;
        }
        if (input.equalsIgnoreCase(":approvals")) {
            printApprovals();
            return true;
        }
        if (input.startsWith(":use")) {
            String requestedThread = input.substring(4).trim();
            if (requestedThread.isEmpty()) {
                System.out.println("Usage: :use <thread-id-prefix>");
                return true;
            }
            ThreadId resolved = resolveThread(requestedThread);
            if (resolved == null) {
                System.out.println("No thread matched: " + requestedThread);
                return true;
            }
            appServerSession.threadResume(new ThreadResumeParams(resolved));
            activeThreadId = resolved;
            System.out.println("Switched to thread: " + shortThreadId(activeThreadId));
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
        System.out.println("Commands: :help, :new, :threads, :skills, :use <thread-id-prefix>, :history, :compact, :approvals, :approve <approval-id-prefix>, :reject <approval-id-prefix> [reason], :interrupt, :steer <message>, exit, quit");
    }

    private void printThreads() {
        List<ThreadSummary> threads = appServerSession.threadList().threads();
        if (threads.isEmpty()) {
            System.out.println("No threads yet.");
            return;
        }
        for (ThreadSummary thread : threads) {
            String marker = thread.threadId().equals(activeThreadId) ? "*" : " ";
            System.out.printf("%s %s  %s  turns=%d  updated=%s%n",
                    marker,
                    shortThreadId(thread.threadId()),
                    thread.title(),
                    thread.turnCount(),
                    TIMESTAMP_FORMAT.format(thread.updatedAt()));
        }
    }

    private ThreadId resolveThread(String prefix) {
        return appServerSession.threadList().threads().stream()
                .map(ThreadSummary::threadId)
                .filter(threadId -> threadId.value().startsWith(prefix))
                .findFirst()
                .orElse(null);
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
        ThreadMemory threadMemory = appServerSession.threadCompactStart(new ThreadCompactStartParams(activeThreadId)).threadMemory();
        System.out.printf("Compacted thread memory %s from %d turns.%n",
                threadMemory.memoryId(),
                threadMemory.compactedTurnCount());
        printThreadMemory(threadMemory);
    }

    private void printThreadMemory(ThreadMemory threadMemory) {
        System.out.printf("[memory] %s turns compacted at %s%n",
                threadMemory.compactedTurnCount(),
                TIMESTAMP_FORMAT.format(threadMemory.createdAt()));
        if (threadMemory.summary() != null && !threadMemory.summary().isBlank()) {
            System.out.println(threadMemory.summary());
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

    private String shortTurnId(TurnId turnId) {
        String value = turnId.value();
        return value.length() <= 8 ? value : value.substring(0, 8);
    }
}
