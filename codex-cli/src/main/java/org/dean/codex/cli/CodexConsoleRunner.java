package org.dean.codex.cli;

import jakarta.annotation.PreDestroy;
import picocli.AutoComplete;
import picocli.CommandLine;
import org.dean.codex.core.approval.CommandApprovalService;
import org.dean.codex.core.appserver.CodexAppServer;
import org.dean.codex.core.appserver.CodexAppServerSession;
import org.dean.codex.cli.command.noninteractive.CommandNotYetWiredException;
import org.dean.codex.cli.command.noninteractive.ExecCommand;
import org.dean.codex.cli.command.noninteractive.LoginCommand;
import org.dean.codex.cli.command.noninteractive.LogoutCommand;
import org.dean.codex.cli.command.noninteractive.NonInteractiveCommand;
import org.dean.codex.cli.command.noninteractive.ReviewCommand;
import org.dean.codex.cli.command.noninteractive.SandboxCommand;
import org.dean.codex.cli.command.root.CodexRootCommand;
import org.dean.codex.cli.command.root.CodexRootCommandLine;
import org.dean.codex.cli.command.session.CompletionSessionCommand;
import org.dean.codex.cli.command.session.ForkSessionCommand;
import org.dean.codex.cli.command.session.ResumeSessionCommand;
import org.dean.codex.cli.config.CliConfigOverrides;
import org.dean.codex.cli.config.CliConfigOverridesMapper;
import org.dean.codex.cli.interactive.SlashCommandParseResult;
import org.dean.codex.cli.interactive.SlashCommandParser;
import org.dean.codex.cli.interactive.SlashCommandRegistry;
import org.dean.codex.cli.interactive.SlashCommandSpec;
import org.dean.codex.cli.launch.CliLaunchRequest;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "codex.cli.enabled", havingValue = "true", matchIfMissing = true)
public class CodexConsoleRunner implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(CodexConsoleRunner.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final SlashCommandRegistry CONSOLE_COMMAND_REGISTRY = SlashCommandRegistry.defaultRegistry();
    private static final long TURN_WAIT_LOG_INTERVAL_SECONDS = 15L;

    private final CodexAppServer codexAppServer;
    private final CommandApprovalService commandApprovalService;
    private final SlashCommandParser consoleCommandParser = new SlashCommandParser(CONSOLE_COMMAND_REGISTRY);
    private CodexAppServerSession appServerSession;
    private ThreadId activeThreadId;
    private int threadSequence = 1;
    private CliLaunchRequest currentLaunchRequest = CliLaunchRequest.of();
    @Value("${codex.cli.show-tool-activity:true}")
    private boolean showToolActivity = true;

    @Autowired
    public CodexConsoleRunner(CodexAppServer codexAppServer,
                              CommandApprovalService commandApprovalService) {
        this.codexAppServer = codexAppServer;
        this.commandApprovalService = commandApprovalService;
    }

    @Override
    public void run(String @NonNull ... args) {
        LaunchMode launchMode = parseLaunchMode(args);
        if (launchMode == null) {
            return;
        }
        this.currentLaunchRequest = launchMode.request();
        switch (launchMode.kind()) {
            case INTERACTIVE -> runInteractiveLoop(launchMode.initialPrompt());
            case RESUME -> launchResumeCommand(launchMode.resumeCommand());
            case FORK -> launchForkCommand(launchMode.forkCommand());
            case COMPLETION -> printCompletionScript(launchMode.completionCommand());
            case NON_INTERACTIVE -> executeNonInteractiveCommand(launchMode.nonInteractiveCommand());
        }
    }

    private void runInteractiveLoop(String initialPrompt) {
        ensureActiveThreadSelected();
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.printf("Codex CLI. Active thread: %s%n", shortThreadId(activeThreadId));
            printHelp();
            if (initialPrompt != null && !initialPrompt.isBlank()) {
                waitForTurn("initial-prompt",
                        () -> appServerSession.turnStart(new TurnStartParams(activeThreadId, initialPrompt)).turn());
            }
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

                waitForTurn("user-prompt",
                        () -> appServerSession.turnStart(new TurnStartParams(activeThreadId, input)).turn());
            }
        }
    }

    @PreDestroy
    void shutdown() throws Exception {
        if (appServerSession != null) {
            appServerSession.close();
        }
    }

    boolean handleConsoleCommand(String input) {
        SlashCommandParseResult result = consoleCommandParser.parse(input);
        if (result.isEmpty() || result.isNonCommand()) {
            return false;
        }
        if (result.isUnknownCommand()) {
            String token = result.commandToken().orElse("(unknown)");
            System.out.println("Unknown command: /" + token + ". Use /help.");
            return true;
        }

        var invocation = result.invocation().orElseThrow();
        return switch (invocation.command().name()) {
            case "help" -> {
                printHelp();
                yield true;
            }
            case "new" -> {
                ensureSessionInitialized();
                activeThreadId = createThread("Thread " + threadSequence++);
                System.out.println("Started new thread: " + shortThreadId(activeThreadId));
                yield true;
            }
            case "threads" -> {
                ensureSessionInitialized();
                printThreads(invocation.arguments());
                yield true;
            }
            case "skills" -> {
                ensureSessionInitialized();
                printSkills();
                yield true;
            }
            case "resume" -> {
                ensureSessionInitialized();
                if (invocation.arguments().isEmpty()) {
                    System.out.println("Usage: /resume <thread-id-prefix>");
                }
                else {
                    switchActiveThread(invocation.arguments());
                }
                yield true;
            }
            case "fork" -> {
                ensureActiveThreadSelected();
                handleForkCommand(invocation.arguments());
                yield true;
            }
            case "archive" -> {
                ensureActiveThreadSelected();
                handleArchiveCommand(invocation.arguments());
                yield true;
            }
            case "unarchive" -> {
                ensureSessionInitialized();
                handleUnarchiveCommand(invocation.arguments());
                yield true;
            }
            case "rollback" -> {
                ensureActiveThreadSelected();
                handleRollbackCommand(invocation.arguments());
                yield true;
            }
            case "subagents" -> {
                ensureActiveThreadSelected();
                printSubagents(invocation.arguments());
                yield true;
            }
            case "agent" -> {
                ensureActiveThreadSelected();
                handleAgentCommand(invocation.arguments());
                yield true;
            }
            case "approvals" -> {
                ensureActiveThreadSelected();
                printApprovals();
                yield true;
            }
            case "history" -> {
                ensureActiveThreadSelected();
                printHistory();
                yield true;
            }
            case "compact" -> {
                ensureActiveThreadSelected();
                compactThread();
                yield true;
            }
            case "interrupt" -> {
                ensureActiveThreadSelected();
                ConversationTurn activeTurn = latestActiveTurn();
                if (activeTurn == null) {
                    System.out.println("No active running turn in the current thread.");
                }
                else {
                    boolean accepted = appServerSession.turnInterrupt(new TurnInterruptParams(activeThreadId, activeTurn.turnId())).accepted();
                    System.out.println(accepted ? "Interrupt requested." : "Interrupt request was not accepted.");
                }
                yield true;
            }
            case "steer" -> {
                ensureActiveThreadSelected();
                String steerInput = invocation.arguments();
                if (steerInput.isEmpty()) {
                    System.out.println("Usage: /steer <message>");
                }
                else {
                    ConversationTurn activeTurn = latestActiveTurn();
                    if (activeTurn == null) {
                        System.out.println("No active running turn in the current thread.");
                    }
                    else {
                        TurnSteerResponse response = appServerSession.turnSteer(new TurnSteerParams(activeThreadId, activeTurn.turnId(), steerInput));
                        System.out.println(response.accepted()
                                ? "Steering accepted for turn " + shortTurnId(response.turnId()) + "."
                                : "Steering request was not accepted.");
                    }
                }
                yield true;
            }
            case "approve" -> {
                ensureActiveThreadSelected();
                String approvalId = invocation.arguments();
                if (approvalId.isEmpty()) {
                    System.out.println("Usage: /approve <approval-id-prefix>");
                }
                else {
                    handleApprovalDecision(approvalId, true, "");
                }
                yield true;
            }
            case "reject" -> {
                ensureActiveThreadSelected();
                String remainder = invocation.arguments();
                if (remainder.isEmpty()) {
                    System.out.println("Usage: /reject <approval-id-prefix> [reason]");
                }
                else {
                    String[] parts = remainder.split("\\s+", 2);
                    handleApprovalDecision(parts[0], false, parts.length > 1 ? parts[1] : "");
                }
                yield true;
            }
            default -> {
                System.out.println("Unknown command: /" + invocation.command().name() + ". Use /help.");
                yield true;
            }
        };
    }

    ThreadId getActiveThreadIdForTest() {
        ensureActiveThreadSelected();
        return activeThreadId;
    }

    CliConfigOverrides getLaunchOverridesForTest() {
        return currentLaunchRequest.configOverrides();
    }

    private LaunchMode parseLaunchMode(String[] args) {
        CommandLine commandLine = CodexRootCommandLine.create();
        commandLine.setUnmatchedArgumentsAllowed(true);
        commandLine.setUnmatchedOptionsArePositionalParams(true);
        try {
            CommandLine.ParseResult parseResult = commandLine.parseArgs(args);
            if (CommandLine.printHelpIfRequested(parseResult)) {
                return null;
            }
            CodexRootCommand rootCommand = commandLine.getCommand();
            CliLaunchRequest request = CliLaunchRequest.of(args, rootCommand.getConfigOverrides());
            CommandLine.ParseResult subcommand = parseResult.subcommand();
            if (subcommand == null) {
                return LaunchMode.interactive(request, blankToNull(rootCommand.getPromptText()));
            }
            String subcommandName = subcommand.commandSpec().name();
            List<String> originalArgs = parseResult.originalArgs();
            int subcommandIndex = originalArgs.indexOf(subcommandName);
            List<String> commandArguments = subcommandIndex < 0
                    ? List.of()
                    : new ArrayList<>(originalArgs.subList(subcommandIndex + 1, originalArgs.size()));
            return switch (subcommand.commandSpec().name()) {
                case "resume" -> LaunchMode.resume(request, ResumeSessionCommand.parse(commandArguments));
                case "fork" -> LaunchMode.fork(request, ForkSessionCommand.parse(commandArguments));
                case "completion" -> LaunchMode.completion(request, CompletionSessionCommand.parse(commandArguments));
                case "exec" -> LaunchMode.nonInteractive(request, ExecCommand.parse(commandArguments));
                case "review" -> LaunchMode.nonInteractive(request, ReviewCommand.parse(commandArguments));
                case "sandbox" -> LaunchMode.nonInteractive(request, SandboxCommand.parse(commandArguments));
                case "login" -> LaunchMode.nonInteractive(request, LoginCommand.parse(commandArguments));
                case "logout" -> LaunchMode.nonInteractive(request, LogoutCommand.parse(commandArguments));
                default -> throw new IllegalArgumentException("Unknown top-level command: " + subcommand.commandSpec().name());
            };
        }
        catch (CommandLine.ParameterException exception) {
            System.err.println(exception.getMessage());
            exception.getCommandLine().usage(System.err);
            return null;
        }
        catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            return null;
        }
    }

    private void launchResumeCommand(ResumeSessionCommand command) {
        ensureSessionInitialized();
        ThreadSummary target = resolveTopLevelTarget(command.sessionId(), command.last(), command.all(), "resume");
        if (target == null) {
            return;
        }
        if (target.archived()) {
            System.out.println("Thread is archived. Unarchive it before resuming: " + shortThreadId(target.threadId()));
            return;
        }
        appServerSession.threadResume(new ThreadResumeParams(target.threadId()));
        activeThreadId = target.threadId();
        runInteractiveLoop(command.prompt());
    }

    private void launchForkCommand(ForkSessionCommand command) {
        ensureSessionInitialized();
        ThreadSummary source = resolveTopLevelTarget(command.sessionId(), command.last(), command.all(), "fork");
        if (source == null) {
            return;
        }
        if (source.archived()) {
            System.out.println("Thread is archived. Unarchive it before forking: " + shortThreadId(source.threadId()));
            return;
        }
        CliConfigOverrides overrides = currentLaunchRequest.configOverrides();
        ThreadSummary forked = appServerSession.threadFork(new ThreadForkParams(
                source.threadId(),
                null,
                null,
                overrides.cd(),
                null,
                overrides.model(),
                null,
                null,
                null,
                null)).thread();
        activeThreadId = forked.threadId();
        System.out.printf("Forked thread %s from %s.%n", shortThreadId(forked.threadId()), shortThreadId(source.threadId()));
        runInteractiveLoop(command.prompt());
    }

    private ThreadSummary resolveTopLevelTarget(String sessionId, boolean last, boolean all, String action) {
        if (all) {
            System.out.println("codex " + action + " --all is not implemented yet.");
            return null;
        }
        if (sessionId != null && !sessionId.isBlank()) {
            return resolveRequiredThread(sessionId, false);
        }
        List<ThreadSummary> candidates = fetchThreads(false);
        if (candidates.isEmpty()) {
            System.out.println("No active threads available to " + action + ".");
            return null;
        }
        if (last || sessionId == null) {
            return candidates.stream()
                    .max(Comparator.comparing(ThreadSummary::updatedAt))
                    .orElse(candidates.get(0));
        }
        return candidates.get(0);
    }

    private void printCompletionScript(CompletionSessionCommand command) {
        String bashScript = AutoComplete.bash("codex", CodexRootCommandLine.create());
        switch (command.shell()) {
            case BASH -> System.out.print(bashScript);
            case ZSH -> {
                System.out.println("#compdef codex");
                System.out.println("autoload -U +X bashcompinit && bashcompinit");
                System.out.print(bashScript);
            }
            case FISH, POWERSHELL ->
                    System.out.printf("# completion for %s is not implemented yet%n",
                            command.shell().name().toLowerCase(Locale.ROOT));
        }
    }

    private void executeNonInteractiveCommand(NonInteractiveCommand command) {
        try {
            command.execute();
        }
        catch (CommandNotYetWiredException exception) {
            System.err.println(exception.getMessage());
        }
    }

    private synchronized void ensureSessionInitialized() {
        if (appServerSession != null) {
            return;
        }
        this.appServerSession = initializeSession(codexAppServer);
        List<ThreadSummary> threads = appServerSession.threadList().threads();
        this.threadSequence = threads.size() + 1;
        this.activeThreadId = threads.isEmpty() ? null : threads.get(0).threadId();
        ensureActiveThreadLoadedAtStartup(threads);
    }

    private void ensureActiveThreadSelected() {
        ensureSessionInitialized();
        if (activeThreadId == null) {
            activeThreadId = createThread("Thread " + threadSequence++);
        }
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

    private void ensureActiveThreadLoadedAtStartup(List<ThreadSummary> startupThreads) {
        if (activeThreadId == null || startupThreads.isEmpty()) {
            return;
        }
        Set<ThreadId> loadedThreadIds = new LinkedHashSet<>(fetchLoadedThreadIds());
        if (ensureThreadLoaded(activeThreadId, startupThreads, loadedThreadIds)) {
            return;
        }

        for (ThreadSummary candidate : startupThreads) {
            if (candidate.threadId().equals(activeThreadId)) {
                continue;
            }
            if (ensureThreadLoaded(candidate.threadId(), startupThreads, loadedThreadIds)) {
                activeThreadId = candidate.threadId();
                return;
            }
        }
        activeThreadId = null;
    }

    private boolean ensureThreadLoaded(ThreadId threadId,
                                       List<ThreadSummary> knownThreads,
                                       Set<ThreadId> loadedThreadIds) {
        if (threadId == null || loadedThreadIds.contains(threadId)) {
            return threadId != null;
        }
        ThreadSummary summary = knownThreads.stream()
                .filter(thread -> thread.threadId().equals(threadId))
                .findFirst()
                .orElse(null);
        if (summary == null || summary.archived()) {
            return false;
        }
        try {
            appServerSession.threadResume(new ThreadResumeParams(threadId));
            loadedThreadIds.add(threadId);
            return true;
        }
        catch (IllegalArgumentException | IllegalStateException exception) {
            return false;
        }
    }

    private void printHelp() {
        System.out.println("Interactive commands use /command syntax.");
        for (SlashCommandSpec command : CONSOLE_COMMAND_REGISTRY.commands()) {
            System.out.printf("  %-36s %s%n", command.syntax(), command.description());
            if (!command.aliases().isEmpty()) {
                System.out.printf("  %-36s aliases: %s%n",
                        "",
                        command.aliases().stream().map(alias -> "/" + alias).collect(Collectors.joining(", ")));
            }
        }
        System.out.println("  exit, quit                           Leave the CLI");
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
                System.out.println("Usage: /threads [all|loaded|archived]");
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
            if (showToolActivity) {
                System.out.println("[tool:start] " + formatToolName(toolCallItem.toolName()) + " -> "
                        + compactText(toolCallItem.target()));
            }
            return;
        }
        if (item instanceof ToolResultItem toolResultItem) {
            if (showToolActivity) {
                System.out.println("[tool:done] " + formatToolName(toolResultItem.toolName()) + " -> "
                        + compactText(toolResultItem.summary()));
            }
            return;
        }
        if (item instanceof ApprovalItem approvalItem) {
            if (showToolActivity) {
                System.out.println("[approval:" + approvalItem.state().name().toLowerCase(Locale.ROOT) + "] "
                        + compactText(blankToPlaceholder(approvalItem.command()))
                        + " -> " + compactText(approvalItem.detail()));
            }
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
            waitForTurn("approval-resume",
                    () -> appServerSession.turnResume(new TurnResumeParams(activeThreadId, approval.turnId())).turn());
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

    private String formatToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "tool";
        }
        return toolName.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private String compactText(String value) {
        String sanitized = blankToPlaceholder(value).replaceAll("\\s+", " ").trim();
        if (sanitized.length() <= 180) {
            return sanitized;
        }
        return sanitized.substring(0, 177) + "...";
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
                    System.out.println("Usage: /fork [thread-id-prefix] [title] or /fork --title <title>");
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
            System.out.println("Usage: /unarchive <thread-id-prefix>");
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
            System.out.println("Usage: /rollback [thread-id-prefix] <turn-count>");
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
            System.out.println("Usage: /rollback [thread-id-prefix] <turn-count>");
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
            System.out.println("Usage: /agent <tree|use> ...");
            return;
        }
        String[] parts = remainder.split("\\s+", 2);
        String subcommand = parts[0].toLowerCase(Locale.ROOT);
        String subArguments = parts.length > 1 ? parts[1].trim() : "";
        switch (subcommand) {
            case "tree" -> printSubagents(subArguments);
            case "use" -> {
                if (subArguments.isEmpty()) {
                    System.out.println("Usage: /agent use <thread-id-prefix>");
                    return;
                }
                switchActiveThread(subArguments);
            }
            default -> System.out.println("Usage: /agent <tree|use> ...");
        }
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

    private void waitForTurn(String lifecycle, TurnStarter turnStarter) {
        ThreadId threadId = activeThreadId;
        long lifecycleStartedNanos = System.nanoTime();
        logger.debug("turn-lifecycle start lifecycle={} thread={}",
                lifecycle,
                safeThreadId(threadId));
        try (TurnNotificationSession session = new TurnNotificationSession(threadId, lifecycle)) {
            logger.debug("turn-lifecycle request-start lifecycle={} thread={}",
                    lifecycle,
                    safeThreadId(threadId));
            RuntimeTurn runtimeTurn = turnStarter.start();
            logger.debug("turn-lifecycle request-accepted lifecycle={} thread={} turn={} status={}",
                    lifecycle,
                    safeThreadId(runtimeTurn.threadId()),
                    safeTurnId(runtimeTurn.turnId()),
                    runtimeTurn.status());
            session.attach(runtimeTurn.turnId());
            session.awaitCompletion();
            logger.debug("turn-lifecycle notification-complete lifecycle={} thread={} turn={} elapsedMs={}",
                    lifecycle,
                    safeThreadId(runtimeTurn.threadId()),
                    safeTurnId(runtimeTurn.turnId()),
                    (System.nanoTime() - lifecycleStartedNanos) / 1_000_000L);
            ConversationTurn completedTurn = appServerSession.threadRead(new ThreadReadParams(activeThreadId)).turns().stream()
                    .filter(turn -> turn.turnId().equals(runtimeTurn.turnId()))
                    .findFirst()
                    .orElseThrow();
            logger.debug("turn-lifecycle thread-read lifecycle={} thread={} turn={} status={} items={} events={}",
                    lifecycle,
                    safeThreadId(activeThreadId),
                    safeTurnId(completedTurn.turnId()),
                    completedTurn.status(),
                    completedTurn.items().size(),
                    completedTurn.events().size());
            if (shouldPrintFinalAnswer(completedTurn)) {
                System.out.println(completedTurn.finalAnswer());
            }
        }
        catch (IllegalArgumentException exception) {
            logger.debug("turn-lifecycle rejected lifecycle={} thread={} message={}",
                    lifecycle,
                    safeThreadId(threadId),
                    exception.getMessage());
            System.out.println(exception.getMessage());
        }
        catch (Exception exception) {
            logger.debug("turn-lifecycle failed lifecycle={} thread={} elapsedMs={}",
                    lifecycle,
                    safeThreadId(threadId),
                    (System.nanoTime() - lifecycleStartedNanos) / 1_000_000L,
                    exception);
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
        private final ThreadId threadId;
        private final String lifecycle;
        private TurnId targetTurnId;

        private TurnNotificationSession(ThreadId threadId, String lifecycle) {
            this.threadId = threadId;
            this.lifecycle = lifecycle == null ? "unknown" : lifecycle;
            try {
                this.subscription = appServerSession.subscribe(this::onNotification);
                logger.debug("turn-notify subscribe lifecycle={} thread={}",
                        this.lifecycle,
                        safeThreadId(threadId));
            }
            catch (Exception exception) {
                throw new IllegalStateException("Unable to subscribe to runtime notifications for thread "
                        + shortThreadId(threadId), exception);
            }
        }

        private synchronized void attach(TurnId turnId) {
            this.targetTurnId = turnId;
            logger.debug("turn-notify attach lifecycle={} thread={} turn={} pendingBuffered={}",
                    lifecycle,
                    safeThreadId(threadId),
                    safeTurnId(turnId),
                    pendingNotifications.size());
            for (AppServerNotification notification : pendingNotifications) {
                if (matchesTurn(notification, turnId)) {
                    process(notification);
                }
            }
            pendingNotifications.clear();
        }

        private void awaitCompletion() {
            try {
                long waitStartedNanos = System.nanoTime();
                while (true) {
                    if (completionLatch.await(TURN_WAIT_LOG_INTERVAL_SECONDS, TimeUnit.SECONDS)) {
                        logger.debug("turn-notify completed lifecycle={} thread={} turn={} waitMs={}",
                                lifecycle,
                                safeThreadId(threadId),
                                safeTurnId(targetTurnId),
                                (System.nanoTime() - waitStartedNanos) / 1_000_000L);
                        return;
                    }
                    logger.debug("turn-notify waiting lifecycle={} thread={} turn={} waitMs={} pendingBuffered={}",
                            lifecycle,
                            safeThreadId(threadId),
                            safeTurnId(targetTurnId),
                            (System.nanoTime() - waitStartedNanos) / 1_000_000L,
                            pendingNotificationCount());
                }
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for runtime notifications.", exception);
            }
        }

        private synchronized void onNotification(AppServerNotification notification) {
            if (targetTurnId == null) {
                pendingNotifications.add(notification);
                logger.debug("turn-notify buffered lifecycle={} thread={} method={} pendingBuffered={}",
                        lifecycle,
                        safeThreadId(threadId),
                        notification == null ? "(null)" : notification.method(),
                        pendingNotifications.size());
                return;
            }
            if (!matchesTurn(notification, targetTurnId)) {
                logger.debug("turn-notify ignored lifecycle={} thread={} targetTurn={} method={}",
                        lifecycle,
                        safeThreadId(threadId),
                        safeTurnId(targetTurnId),
                        notification == null ? "(null)" : notification.method());
                return;
            }
            logger.debug("turn-notify matched lifecycle={} thread={} turn={} method={}",
                    lifecycle,
                    safeThreadId(threadId),
                    safeTurnId(targetTurnId),
                    notification == null ? "(null)" : notification.method());
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
                logger.debug("turn-notify item lifecycle={} thread={} turn={} itemType={}",
                        lifecycle,
                        safeThreadId(threadId),
                        safeTurnId(targetTurnId),
                        itemNotification.item().getClass().getSimpleName());
                printItem(itemNotification.item());
            }
            if (notification instanceof TurnCompletedNotification) {
                logger.debug("turn-notify completion lifecycle={} thread={} turn={}",
                        lifecycle,
                        safeThreadId(threadId),
                        safeTurnId(targetTurnId));
                completionLatch.countDown();
            }
        }

        @Override
        public void close() throws Exception {
            logger.debug("turn-notify unsubscribe lifecycle={} thread={} turn={}",
                    lifecycle,
                    safeThreadId(threadId),
                    safeTurnId(targetTurnId));
            subscription.close();
        }

        private synchronized int pendingNotificationCount() {
            return pendingNotifications.size();
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

    private String safeThreadId(ThreadId threadId) {
        return threadId == null ? "(none)" : shortThreadId(threadId);
    }

    private String safeTurnId(TurnId turnId) {
        return turnId == null ? "(none)" : shortTurnId(turnId);
    }

    private record LaunchMode(CliLaunchRequest request,
                              Kind kind,
                              String initialPrompt,
                              ResumeSessionCommand resumeCommand,
                              ForkSessionCommand forkCommand,
                              CompletionSessionCommand completionCommand,
                              NonInteractiveCommand nonInteractiveCommand) {

        static LaunchMode interactive(CliLaunchRequest request, String initialPrompt) {
            return new LaunchMode(request, Kind.INTERACTIVE, initialPrompt, null, null, null, null);
        }

        static LaunchMode resume(CliLaunchRequest request, ResumeSessionCommand command) {
            return new LaunchMode(request, Kind.RESUME, null, command, null, null, null);
        }

        static LaunchMode fork(CliLaunchRequest request, ForkSessionCommand command) {
            return new LaunchMode(request, Kind.FORK, null, null, command, null, null);
        }

        static LaunchMode completion(CliLaunchRequest request, CompletionSessionCommand command) {
            return new LaunchMode(request, Kind.COMPLETION, null, null, null, command, null);
        }

        static LaunchMode nonInteractive(CliLaunchRequest request, NonInteractiveCommand command) {
            return new LaunchMode(request, Kind.NON_INTERACTIVE, null, null, null, null, command);
        }

        private enum Kind {
            INTERACTIVE,
            RESUME,
            FORK,
            COMPLETION,
            NON_INTERACTIVE
        }
    }
}
