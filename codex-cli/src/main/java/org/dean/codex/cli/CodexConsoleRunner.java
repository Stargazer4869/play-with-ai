package org.dean.codex.cli;

import org.dean.codex.core.approval.CommandApprovalService;
import org.dean.codex.core.agent.TurnExecutor;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.protocol.approval.ApprovalStatus;
import org.dean.codex.protocol.approval.CommandApprovalRequest;
import org.dean.codex.protocol.conversation.ConversationTurn;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.ThreadSummary;
import org.dean.codex.protocol.event.CodexTurnResult;
import org.dean.codex.protocol.event.TurnEvent;
import org.dean.codex.protocol.tool.ShellCommandResult;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Scanner;

@Component
public class CodexConsoleRunner implements CommandLineRunner {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final TurnExecutor turnExecutor;
    private final ConversationStore conversationStore;
    private final CommandApprovalService commandApprovalService;
    private ThreadId activeThreadId;
    private int threadSequence;

    @Autowired
    public CodexConsoleRunner(TurnExecutor turnExecutor,
                              ConversationStore conversationStore,
                              CommandApprovalService commandApprovalService) {
        this.turnExecutor = turnExecutor;
        this.conversationStore = conversationStore;
        this.commandApprovalService = commandApprovalService;
        List<ThreadSummary> threads = conversationStore.listThreads();
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

                CodexTurnResult result = turnExecutor.executeTurn(activeThreadId, input);
                printEvents(result.events());
                System.out.println(result.finalAnswer());
            }
        }
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
            activeThreadId = resolved;
            System.out.println("Switched to thread: " + shortThreadId(activeThreadId));
            return true;
        }
        if (input.equalsIgnoreCase(":history")) {
            printHistory();
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

    private ThreadId createThread(String title) {
        return conversationStore.createThread(title);
    }

    private void printHelp() {
        System.out.println("Commands: :help, :new, :threads, :use <thread-id-prefix>, :history, :approvals, :approve <approval-id-prefix>, :reject <approval-id-prefix> [reason], exit, quit");
    }

    private void printThreads() {
        List<ThreadSummary> threads = conversationStore.listThreads();
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
        return conversationStore.listThreads().stream()
                .map(ThreadSummary::threadId)
                .filter(threadId -> threadId.value().startsWith(prefix))
                .findFirst()
                .orElse(null);
    }

    private void printHistory() {
        List<ConversationTurn> turns = conversationStore.turns(activeThreadId);
        if (turns.isEmpty()) {
            System.out.println("No turns in the active thread yet.");
            return;
        }
        for (ConversationTurn turn : turns) {
            System.out.printf("[%s] USER: %s%n", turn.status(), turn.userInput());
            for (TurnEvent event : turn.events()) {
                System.out.printf("  - %s: %s%n", event.type(), event.detail());
            }
            if (turn.finalAnswer() != null && !turn.finalAnswer().isBlank()) {
                System.out.printf("ASSISTANT: %s%n", turn.finalAnswer());
            }
        }
    }

    private void printEvents(List<TurnEvent> events) {
        for (TurnEvent event : events) {
            String prefix = switch (event.type()) {
                case "tool.call" -> "[tool]";
                case "tool.result" -> "[tool-result]";
                case "approval.required", "approval.blocked", "approval.approved", "approval.rejected", "approval.result" -> "[approval]";
                default -> "[event]";
            };
            System.out.println(prefix + " " + event.detail());
        }
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
            CodexTurnResult resumed = turnExecutor.resumeTurn(activeThreadId, approval.turnId());
            printEvents(resumed.events());
            System.out.println(resumed.finalAnswer());
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
}
