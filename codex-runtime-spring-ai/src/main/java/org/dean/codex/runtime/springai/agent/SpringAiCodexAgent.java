package org.dean.codex.runtime.springai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dean.codex.core.agent.AgentControl;
import org.dean.codex.core.approval.CommandApprovalService;
import org.dean.codex.core.agent.CodexAgent;
import org.dean.codex.core.agent.TurnControl;
import org.dean.codex.core.context.ContextManager;
import org.dean.codex.core.context.ThreadContextReconstructionService;
import org.dean.codex.core.skill.ResolvedSkill;
import org.dean.codex.core.skill.SkillService;
import org.dean.codex.core.tool.local.FilePatchTool;
import org.dean.codex.core.tool.local.FileReaderTool;
import org.dean.codex.core.tool.local.FileSearchTool;
import org.dean.codex.core.tool.local.FileWriterTool;
import org.dean.codex.core.tool.local.ShellCommandTool;
import org.dean.codex.protocol.planning.EditPlan;
import org.dean.codex.protocol.planning.PlannedEdit;
import org.dean.codex.protocol.planning.PlannedEditType;
import org.dean.codex.protocol.conversation.ItemId;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.event.CodexTurnResult;
import org.dean.codex.protocol.event.TurnEvent;
import org.dean.codex.protocol.agent.AgentMessage;
import org.dean.codex.protocol.agent.AgentSpawnRequest;
import org.dean.codex.protocol.agent.AgentSummary;
import org.dean.codex.protocol.agent.AgentWaitResult;
import org.dean.codex.protocol.item.AgentMessageItem;
import org.dean.codex.protocol.item.ApprovalItem;
import org.dean.codex.protocol.item.ApprovalState;
import org.dean.codex.protocol.item.PlanItem;
import org.dean.codex.protocol.item.RuntimeErrorItem;
import org.dean.codex.protocol.item.SkillUseItem;
import org.dean.codex.protocol.item.ToolCallItem;
import org.dean.codex.protocol.item.ToolResultItem;
import org.dean.codex.protocol.item.TurnItem;
import org.dean.codex.protocol.item.UserMessageItem;
import org.dean.codex.protocol.approval.CommandApprovalRequest;
import org.dean.codex.protocol.context.ReconstructedThreadContext;
import org.dean.codex.protocol.context.ReconstructedTurnActivity;
import org.dean.codex.protocol.skill.SkillMetadata;
import org.dean.codex.protocol.tool.CommandApprovalDecision;
import org.dean.codex.runtime.springai.config.CodexProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Component
public class SpringAiCodexAgent implements CodexAgent {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiCodexAgent.class);

    private final ChatClient chatClient;
    private final FileReaderTool fileReaderTool;
    private final FileSearchTool fileSearchTool;
    private final FilePatchTool filePatchTool;
    private final FileWriterTool fileWriterTool;
    private final ShellCommandTool shellCommandTool;
    private final CommandApprovalService commandApprovalService;
    private final Supplier<AgentControl> agentControlSupplier;
    private final ThreadContextReconstructionService threadContextReconstructionService;
    private final ContextManager contextManager;
    private final SkillService skillService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path workspaceRoot;
    private final int maxSteps;
    private final int maxActionsPerStep;
    private final int autoCompactTokenLimit;
    private final int contextWindow;

    @Autowired
    public SpringAiCodexAgent(ChatClient.Builder chatClientBuilder,
                              FileReaderTool fileReaderTool,
                              FileSearchTool fileSearchTool,
                              FilePatchTool filePatchTool,
                              FileWriterTool fileWriterTool,
                              ShellCommandTool shellCommandTool,
                              CommandApprovalService commandApprovalService,
                              ObjectProvider<AgentControl> agentControlProvider,
                              ThreadContextReconstructionService threadContextReconstructionService,
                              ContextManager contextManager,
                              SkillService skillService,
                              @Qualifier("codexWorkspaceRoot") Path workspaceRoot,
                              CodexProperties codexProperties) {
        this(chatClientBuilder,
                fileReaderTool,
                fileSearchTool,
                filePatchTool,
                fileWriterTool,
                shellCommandTool,
                commandApprovalService,
                agentControlProvider == null ? () -> null : agentControlProvider::getIfAvailable,
                threadContextReconstructionService,
                contextManager,
                skillService,
                workspaceRoot,
                codexProperties);
    }

    SpringAiCodexAgent(ChatClient.Builder chatClientBuilder,
                       FileReaderTool fileReaderTool,
                       FileSearchTool fileSearchTool,
                       FilePatchTool filePatchTool,
                       FileWriterTool fileWriterTool,
                       ShellCommandTool shellCommandTool,
                       CommandApprovalService commandApprovalService,
                       AgentControl agentControl,
                       ThreadContextReconstructionService threadContextReconstructionService,
                       ContextManager contextManager,
                       SkillService skillService,
                       Path workspaceRoot,
                       CodexProperties codexProperties) {
        this(chatClientBuilder,
                fileReaderTool,
                fileSearchTool,
                filePatchTool,
                fileWriterTool,
                shellCommandTool,
                commandApprovalService,
                () -> agentControl,
                threadContextReconstructionService,
                contextManager,
                skillService,
                workspaceRoot,
                codexProperties);
    }

    private SpringAiCodexAgent(ChatClient.Builder chatClientBuilder,
                               FileReaderTool fileReaderTool,
                               FileSearchTool fileSearchTool,
                               FilePatchTool filePatchTool,
                               FileWriterTool fileWriterTool,
                               ShellCommandTool shellCommandTool,
                               CommandApprovalService commandApprovalService,
                               Supplier<AgentControl> agentControlSupplier,
                               ThreadContextReconstructionService threadContextReconstructionService,
                               ContextManager contextManager,
                               SkillService skillService,
                               Path workspaceRoot,
                               CodexProperties codexProperties) {
        this.chatClient = chatClientBuilder.clone()
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
        this.fileReaderTool = fileReaderTool;
        this.fileSearchTool = fileSearchTool;
        this.filePatchTool = filePatchTool;
        this.fileWriterTool = fileWriterTool;
        this.shellCommandTool = shellCommandTool;
        this.commandApprovalService = commandApprovalService;
        this.agentControlSupplier = agentControlSupplier == null ? () -> null : agentControlSupplier;
        this.threadContextReconstructionService = threadContextReconstructionService;
        this.contextManager = contextManager;
        this.skillService = skillService;
        this.workspaceRoot = workspaceRoot;
        CodexProperties.Agent agent = codexProperties.getAgent();
        this.maxSteps = Math.max(1, agent.getMaxSteps());
        this.maxActionsPerStep = Math.max(1, agent.getMaxActionsPerStep());
        CodexProperties.Model model = codexProperties.getModel();
        this.autoCompactTokenLimit = Math.max(0, model.getAutoCompactTokenLimit());
        this.contextWindow = Math.max(0, model.getContextWindow());
    }

    @Override
    public synchronized CodexTurnResult handleTurn(ThreadId threadId, TurnId turnId, String input) {
        return handleTurn(threadId, turnId, input, null);
    }

    @Override
    public synchronized CodexTurnResult handleTurn(ThreadId threadId, TurnId turnId, String input, Consumer<TurnItem> itemConsumer) {
        return handleTurn(threadId, turnId, input, itemConsumer, new TurnControl() { });
    }

    @Override
    public synchronized CodexTurnResult handleTurn(ThreadId threadId,
                                                   TurnId turnId,
                                                   String input,
                                                   Consumer<TurnItem> itemConsumer,
                                                   TurnControl turnControl) {
        String safeInput = input == null ? "" : input.trim();
        TurnControl safeTurnControl = turnControl == null ? new TurnControl() { } : turnControl;
        try {
            List<ResolvedSkill> selectedSkills = selectedSkillsForInput(safeInput);
            List<SkillMetadata> availableSkills = skillService.listSkills(false);
            List<TurnItem> preludeItems = new ArrayList<>();
            if (!selectedSkills.isEmpty()) {
                SkillUseItem skillUseItem = skillUseItem(selectedSkills.stream().map(ResolvedSkill::metadata).toList());
                emitItem(preludeItems, itemConsumer, skillUseItem);
            }
            ExecutionOutcome outcome = runPlanningLoop(
                    threadId,
                    turnId,
                    safeInput,
                    itemConsumer,
                    safeTurnControl,
                    preludeItems,
                    selectedSkills,
                    availableSkills);
            String finalAnswer = outcome.finalAnswer() == null || outcome.finalAnswer().isBlank()
                    ? "I couldn't produce a response for that request."
                    : outcome.finalAnswer();
            return new CodexTurnResult(threadId, turnId, outcome.status(), outcome.items(), finalAnswer);
        }
        catch (Exception exception) {
            logger.debug("Codex turn failed for thread {} with input: {}", threadId.value(), safeInput, exception);
            RuntimeErrorItem errorItem = runtimeErrorItem(safeMessage(exception.getMessage()));
            emitItem(new ArrayList<>(), itemConsumer, errorItem);
            return new CodexTurnResult(
                    threadId,
                    turnId,
                    TurnStatus.FAILED,
                    List.of(errorItem),
                    "The Codex agent hit an error: " + safeMessage(exception.getMessage()));
        }
    }

    private ExecutionOutcome runPlanningLoop(ThreadId threadId,
                                             TurnId turnId,
                                             String input,
                                             Consumer<TurnItem> itemConsumer,
                                             TurnControl turnControl,
                                             List<TurnItem> preludeItems,
                                             List<ResolvedSkill> selectedSkills,
                                             List<SkillMetadata> availableSkills) {
        List<TurnItem> items = new ArrayList<>(preludeItems);
        StringBuilder scratchpad = new StringBuilder();
        String lastObservation = "(none)";
        boolean skipNextPreSamplingAutoCompaction = false;

        for (int step = 1; step <= maxSteps; step++) {
            if (turnControl.interruptionRequested()) {
                return interruptedOutcome(items, itemConsumer);
            }
            List<String> steeringInputs = turnControl.drainSteeringInputs();
            if (!steeringInputs.isEmpty()) {
                steeringInputs.forEach(steeringInput ->
                        emitItem(items, itemConsumer, new UserMessageItem(new ItemId(UUID.randomUUID().toString()), steeringInput, Instant.now())));
            }
            if (skipNextPreSamplingAutoCompaction) {
                skipNextPreSamplingAutoCompaction = false;
            }
            else {
                maybeAutoCompactBeforeSampling(
                        threadId,
                        input,
                        scratchpad.toString(),
                        step,
                        selectedSkills,
                        availableSkills,
                        steeringInputs);
            }
            PlannerStep decision = requestDecision(
                    threadId,
                    input,
                    scratchpad.toString(),
                    step,
                    selectedSkills,
                    availableSkills,
                    steeringInputs);
            if (turnControl.interruptionRequested()) {
                return interruptedOutcome(items, itemConsumer);
            }
            if (decision.editPlan() != null && !decision.editPlan().edits().isEmpty()) {
                emitItem(items, itemConsumer, planItem(decision.editPlan()));
            }
            if (decision.isFinished()) {
                String finalAnswer = decision.finalAnswer() == null || decision.finalAnswer().isBlank()
                        ? "I have finished the task, but the model did not provide a final answer."
                        : decision.finalAnswer();
                emitItem(items, itemConsumer, agentMessageItem(finalAnswer));
                return new ExecutionOutcome(
                        TurnStatus.COMPLETED,
                        List.copyOf(items),
                        finalAnswer);
            }

            String observation;
            BatchExecutionOutcome batchOutcome = null;
            if (decision.validationError() == null) {
                batchOutcome = executeActions(threadId, turnId, decision.actions(), items, itemConsumer);
                observation = batchOutcome.observation();
            }
            else {
                observation = createErrorObservation(decision.validationError());
                emitItem(items, itemConsumer, runtimeErrorItem(decision.validationError()));
            }

            lastObservation = observation;
            scratchpad.append("Step ").append(step).append(':').append(System.lineSeparator())
                    .append("Summary: ").append(blankToPlaceholder(decision.summary())).append(System.lineSeparator())
                    .append("Edit plan: ").append(summarizeEditPlan(decision.editPlan())).append(System.lineSeparator())
                    .append("Actions: ").append(describeActions(decision.actions())).append(System.lineSeparator())
                    .append("Observation: ").append(observation).append(System.lineSeparator()).append(System.lineSeparator());

            if (batchOutcome != null && batchOutcome.awaitingApproval()) {
                String approvalList = batchOutcome.approvalIds().isEmpty()
                        ? "(unknown approval id)"
                        : batchOutcome.approvalIds().stream()
                        .map(this::shortApprovalId)
                        .collect(Collectors.joining(", "));
                String approvalMessage = "Approval required for command execution. Review with :approvals and continue with :approve <id-prefix> or :reject <id-prefix>. Pending approval ids: " + approvalList;
                emitItem(items, itemConsumer, agentMessageItem(approvalMessage));
                return new ExecutionOutcome(
                        TurnStatus.AWAITING_APPROVAL,
                        List.copyOf(items),
                        approvalMessage);
            }
            if (turnControl.interruptionRequested()) {
                return interruptedOutcome(items, itemConsumer);
            }
            boolean compacted = maybeAutoCompactAfterActions(
                    threadId,
                    input,
                    scratchpad.toString(),
                    step + 1,
                    selectedSkills,
                    availableSkills,
                    List.of());
            if (compacted) {
                skipNextPreSamplingAutoCompaction = true;
                continue;
            }
        }

        emitItem(items, itemConsumer, runtimeErrorItem("Stopped after " + maxSteps + " planner steps."));
        String finalAnswer = "I stopped after %d planner steps without reaching a final answer. Last observation:%n%s"
                .formatted(maxSteps, lastObservation);
        emitItem(items, itemConsumer, agentMessageItem(finalAnswer));
        return new ExecutionOutcome(
                TurnStatus.COMPLETED,
                List.copyOf(items),
                finalAnswer);
    }

    private ExecutionOutcome interruptedOutcome(List<TurnItem> items, Consumer<TurnItem> itemConsumer) {
        String finalAnswer = "Turn interrupted.";
        emitItem(items, itemConsumer, agentMessageItem(finalAnswer));
        return new ExecutionOutcome(
                TurnStatus.INTERRUPTED,
                List.copyOf(items),
                finalAnswer);
    }

    protected PlannerStep requestDecision(ThreadId threadId,
                                          String input,
                                          String scratchpad,
                                          int step,
                                          List<ResolvedSkill> selectedSkills,
                                          List<SkillMetadata> availableSkills,
                                          List<String> steeringInputs) {
        String systemPrompt = buildSystemPrompt(availableSkills);
        ReconstructedThreadContext reconstructedContext = threadContextReconstructionService.reconstruct(threadId);
        String userPrompt = buildUserPrompt(reconstructedContext, input, scratchpad, step, selectedSkills, steeringInputs);
        logger.debug("Codex planner request step {}\nSystem:\n{}\nUser:\n{}", step, systemPrompt, userPrompt);

        String response = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
        logger.debug("Codex planner response step {}\n{}", step, response == null ? "(null)" : response);

        PlannerStep decision = parseDecision(response);
        logger.debug("Codex planner parsed step {} actions={} finished={} summary={}",
                step, decision.actions().size(), decision.isFinished(), blankToPlaceholder(decision.summary()));
        return decision;
    }

    private boolean maybeAutoCompactBeforeSampling(ThreadId threadId,
                                                   String input,
                                                   String scratchpad,
                                                   int step,
                                                   List<ResolvedSkill> selectedSkills,
                                                   List<SkillMetadata> availableSkills,
                                                   List<String> steeringInputs) {
        return maybeAutoCompact(threadId, input, scratchpad, step, selectedSkills, availableSkills, steeringInputs,
                "before sampling");
    }

    private boolean maybeAutoCompactAfterActions(ThreadId threadId,
                                                 String input,
                                                 String scratchpad,
                                                 int step,
                                                 List<ResolvedSkill> selectedSkills,
                                                 List<SkillMetadata> availableSkills,
                                                 List<String> steeringInputs) {
        return maybeAutoCompact(threadId, input, scratchpad, step, selectedSkills, availableSkills, steeringInputs,
                "after actions");
    }

    private boolean maybeAutoCompact(ThreadId threadId,
                                     String input,
                                     String scratchpad,
                                     int step,
                                     List<ResolvedSkill> selectedSkills,
                                     List<SkillMetadata> availableSkills,
                                     List<String> steeringInputs,
                                     String phase) {
        int limit = effectiveAutoCompactTokenLimit();
        if (limit <= 0) {
            return false;
        }

        int estimatedTokens = estimatePlannerPromptTokens(
                threadId,
                input,
                scratchpad,
                step,
                selectedSkills,
                availableSkills,
                steeringInputs);
        if (estimatedTokens <= limit) {
            return false;
        }

        logger.debug("Auto-compacting thread {} {} at step {}: estimatedTokens={} limit={}",
                threadId.value(),
                phase,
                step,
                estimatedTokens,
                limit);
        contextManager.compactThread(threadId);
        return true;
    }

    private int estimatePlannerPromptTokens(ThreadId threadId,
                                            String input,
                                            String scratchpad,
                                            int step,
                                            List<ResolvedSkill> selectedSkills,
                                            List<SkillMetadata> availableSkills,
                                            List<String> steeringInputs) {
        ReconstructedThreadContext reconstructedContext = threadContextReconstructionService.reconstruct(threadId);
        String systemPrompt = buildSystemPrompt(availableSkills);
        String userPrompt = buildUserPrompt(reconstructedContext, input, scratchpad, step, selectedSkills, steeringInputs);
        return estimateTokens(systemPrompt) + estimateTokens(userPrompt);
    }

    private int effectiveAutoCompactTokenLimit() {
        int limit = autoCompactTokenLimit;
        if (contextWindow > 0) {
            limit = limit <= 0 ? contextWindow : Math.min(limit, contextWindow);
        }
        return Math.max(0, limit);
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (text.length() + 3) / 4);
    }

    BatchExecutionOutcome executeActions(ThreadId threadId,
                                         TurnId turnId,
                                         List<ToolActionRequest> actions,
                                         List<TurnItem> items,
                                         Consumer<TurnItem> itemConsumer) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> approvalIds = new ArrayList<>();
        boolean awaitingApproval = false;
        for (int index = 0; index < actions.size(); index++) {
            ToolActionRequest action = actions.get(index);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", index + 1);
            entry.put("action", action.action());
            entry.put("target", describeTarget(action));
            ActionExecutionOutcome actionOutcome = executeAction(threadId, turnId, action, items, itemConsumer);
            entry.put("result", parseObservation(actionOutcome.observation()));
            results.add(entry);
            approvalIds.addAll(actionOutcome.approvalIds());
            if (actionOutcome.awaitingApproval()) {
                awaitingApproval = true;
                break;
            }
        }

        try {
            return new BatchExecutionOutcome(
                    objectMapper.writeValueAsString(Map.of("results", results)),
                    awaitingApproval,
                    List.copyOf(approvalIds));
        }
        catch (Exception exception) {
            return new BatchExecutionOutcome(createErrorObservation(exception.getMessage()), awaitingApproval, List.copyOf(approvalIds));
        }
    }

    ActionExecutionOutcome executeAction(ThreadId threadId,
                                         TurnId turnId,
                                         ToolActionRequest action,
                                         List<TurnItem> items,
                                         Consumer<TurnItem> itemConsumer) {
        emitItem(items, itemConsumer, toolCallItem(action.action().name(), describeTarget(action)));
        String observation;
        try {
            observation = switch (action.action()) {
                case READ_FILE -> objectMapper.writeValueAsString(fileReaderTool.readFile(action.path()));
                case SEARCH_FILES -> objectMapper.writeValueAsString(fileSearchTool.search(action.query(), action.path()));
                case APPLY_PATCH -> objectMapper.writeValueAsString(
                        filePatchTool.applyPatch(action.path(), action.oldText(), action.newText(), action.replaceAll()));
                case WRITE_FILE -> objectMapper.writeValueAsString(fileWriterTool.writeFile(action.path(), action.content()));
                case RUN_COMMAND -> objectMapper.writeValueAsString(shellCommandTool.runCommand(action.command()));
                case SPAWN_AGENT -> {
                    AgentControl agentControl = requireAgentControl();
                    AgentSummary spawnedAgent = agentControl.spawnAgent(new AgentSpawnRequest(
                            threadId,
                            action.taskName(),
                            firstNonBlank(action.prompt(), action.taskName()),
                            action.nickname(),
                            action.role(),
                            action.depth(),
                            action.modelProvider(),
                            action.model(),
                            action.cwd()));
                    LinkedHashMap<String, Object> response = new LinkedHashMap<>();
                    response.put("success", true);
                    response.put("action", "spawn_agent");
                    response.put("parentThreadId", threadId.value());
                    response.put("threadId", spawnedAgent.threadId().value());
                    response.put("status", spawnedAgent.status());
                    response.put("agent", spawnedAgent);
                    response.put("taskName", action.taskName());
                    response.put("prompt", firstNonBlank(action.prompt(), action.taskName()));
                    yield objectMapper.writeValueAsString(response);
                }
                case SEND_INPUT -> {
                    AgentControl agentControl = requireAgentControl();
                    ThreadId agentThreadId = new ThreadId(action.threadId());
                    AgentSummary agentSummary = agentControl.sendInput(
                            agentThreadId,
                            new AgentMessage(threadId, agentThreadId, action.content(), Instant.now()),
                            action.interrupt());
                    LinkedHashMap<String, Object> response = new LinkedHashMap<>();
                    response.put("success", true);
                    response.put("action", "send_input");
                    response.put("senderThreadId", threadId.value());
                    response.put("threadId", agentThreadId.value());
                    response.put("status", agentSummary.status());
                    response.put("agent", agentSummary);
                    response.put("content", action.content());
                    response.put("interrupt", action.interrupt());
                    yield objectMapper.writeValueAsString(response);
                }
                case WAIT_AGENT -> {
                    AgentControl agentControl = requireAgentControl();
                    AgentWaitResult waitResult = agentControl.waitAgent(action.threadIds(), action.timeoutMillis() == null ? 1000L : action.timeoutMillis());
                    LinkedHashMap<String, Object> response = new LinkedHashMap<>();
                    response.put("success", true);
                    response.put("action", "wait_agent");
                    response.put("threadId", waitResult.threadId() == null ? null : waitResult.threadId().value());
                    response.put("turnId", waitResult.turnId() == null ? null : waitResult.turnId().value());
                    response.put("previousStatus", waitResult.previousStatus());
                    response.put("status", waitResult.status());
                    response.put("timedOut", waitResult.timedOut());
                    response.put("message", waitResult.message());
                    response.put("finalAnswer", waitResult.finalAnswer());
                    response.put("completedAt", waitResult.completedAt());
                    response.put("result", waitResult);
                    response.put("threadIds", action.threadIds().stream().map(ThreadId::value).toList());
                    response.put("timeoutMillis", action.timeoutMillis() == null ? 1000L : action.timeoutMillis());
                    yield objectMapper.writeValueAsString(response);
                }
                case RESUME_AGENT -> {
                    AgentControl agentControl = requireAgentControl();
                    ThreadId agentThreadId = new ThreadId(action.threadId());
                    AgentSummary agentSummary = agentControl.resumeAgent(agentThreadId);
                    LinkedHashMap<String, Object> response = new LinkedHashMap<>();
                    response.put("success", true);
                    response.put("action", "resume_agent");
                    response.put("threadId", agentThreadId.value());
                    response.put("status", agentSummary.status());
                    response.put("agent", agentSummary);
                    yield objectMapper.writeValueAsString(response);
                }
                case CLOSE_AGENT -> {
                    AgentControl agentControl = requireAgentControl();
                    ThreadId agentThreadId = new ThreadId(action.threadId());
                    AgentSummary agentSummary = agentControl.closeAgent(agentThreadId);
                    LinkedHashMap<String, Object> response = new LinkedHashMap<>();
                    response.put("success", true);
                    response.put("action", "close_agent");
                    response.put("threadId", agentThreadId.value());
                    response.put("status", agentSummary.status());
                    response.put("closed", agentSummary.closed());
                    response.put("agent", agentSummary);
                    yield objectMapper.writeValueAsString(response);
                }
                case LIST_AGENTS -> {
                    AgentControl agentControl = requireAgentControl();
                    ThreadId parentThreadId = action.threadId().isBlank() ? threadId : new ThreadId(action.threadId());
                    List<AgentSummary> agents = agentControl.listAgents(parentThreadId, action.recursive());
                    LinkedHashMap<String, Object> response = new LinkedHashMap<>();
                    response.put("success", true);
                    response.put("action", "list_agents");
                    response.put("parentThreadId", parentThreadId.value());
                    response.put("recursive", action.recursive());
                    response.put("agentCount", agents.size());
                    response.put("agents", agents);
                    yield objectMapper.writeValueAsString(response);
                }
            };
        }
        catch (Exception exception) {
            observation = createErrorObservation(exception.getMessage());
        }
        ActionExecutionOutcome outcome = enrichApprovalObservation(threadId, turnId, action, observation, items, itemConsumer);
        observation = outcome.observation();
        emitItem(items, itemConsumer, toolResultItem(action.action().name(), summarizeToolResult(action.action(), observation)));
        return outcome;
    }

    PlannerStep parseDecision(String response) {
        try {
            String cleaned = stripCodeFences(response);
            JsonNode root = objectMapper.readTree(cleaned);
            String summary = firstNonBlank(textValue(root.get("summary")), textValue(root.get("thought")));
            String finalAnswer = textValue(root.get("finalAnswer"));
            EditPlan editPlan = parseEditPlan(root.get("editPlan"));
            List<ToolActionRequest> actions = parseActions(root);

            if (!finalAnswer.isBlank()) {
                if (!actions.isEmpty()) {
                    return PlannerStep.invalid(summary, editPlan, actions,
                            "Return either actions or finalAnswer, but not both in the same planner step.");
                }
                return PlannerStep.finish(summary, editPlan, finalAnswer);
            }

            if (actions.isEmpty()) {
                return PlannerStep.invalid(summary, editPlan, List.of(),
                        "I could not determine any tool actions from the model response.");
            }
            if (actions.size() > maxActionsPerStep) {
                return PlannerStep.invalid(summary, editPlan, actions,
                        "The model selected %d actions, exceeding the configured per-step limit of %d."
                                .formatted(actions.size(), maxActionsPerStep));
            }

            String validationError = validateActions(actions);
            return validationError == null
                    ? PlannerStep.actions(summary, editPlan, actions)
                    : PlannerStep.invalid(summary, editPlan, actions, validationError);
        }
        catch (Exception exception) {
            String fallbackMessage = response == null || response.isBlank()
                    ? "I couldn't parse a valid planner step from the model response."
                    : response.trim();
            return PlannerStep.invalid("The model returned a non-JSON response.", null, List.of(),
                    "Invalid planner JSON response: " + fallbackMessage);
        }
    }

    String buildSystemPrompt(List<SkillMetadata> availableSkills) {
        String basePrompt = """
                You are Codex, a coding agent running inside a local workspace.
                Workspace root: %s

                Your job is to make progress through short, verifiable tool batches.
                Available actions:
                - READ_FILE: read a file relative to the workspace root
                - SEARCH_FILES: search for text or regex matches across the workspace or inside a scoped path
                - APPLY_PATCH: replace exact old text with new text inside an existing file
                - WRITE_FILE: create or overwrite a file relative to the workspace root
                - RUN_COMMAND: run a zsh command from the workspace root, subject to approval policy
                - spawn_agent: spawn a delegated sub-agent from the current thread
                - send_input: send a message to an existing sub-agent thread
                - wait_agent: wait for one or more sub-agents to change status or mailbox state
                - resume_agent: resume a paused or waiting sub-agent thread
                - close_agent: close a sub-agent thread subtree
                - list_agents: list sub-agents under the current thread or a requested parent thread

                Rules:
                - Return JSON only. Do not wrap it in prose.
                - Use this schema exactly:
                  {
                    "summary": "brief summary of the next step",
                    "editPlan": {
                      "summary": "optional edit plan for intended file changes",
                      "edits": [
                        {
                          "path": "relative/path",
                          "type": "CREATE | MODIFY | DELETE | VERIFY",
                          "description": "what you intend to change"
                        }
                      ]
                    },
                    "actions": [
                      {
                        "action": "READ_FILE | SEARCH_FILES | APPLY_PATCH | WRITE_FILE | RUN_COMMAND",
                        "path": "relative/path/when-needed",
                        "query": "search query when searching",
                        "oldText": "exact text to replace when applying a patch",
                        "newText": "replacement text when applying a patch",
                        "replaceAll": false,
                        "content": "file content when writing",
                        "command": "shell command when running one"
                      },
                      {
                        "action": "spawn_agent",
                        "taskName": "delegate a focused task",
                        "prompt": "optional task prompt for the child agent",
                        "nickname": "optional agent nickname",
                        "role": "optional agent role",
                        "depth": 1,
                        "modelProvider": "optional model provider",
                        "model": "optional model name",
                        "cwd": "optional child workspace cwd"
                      },
                      {
                        "action": "send_input",
                        "threadId": "agent-thread-id",
                        "content": "message to send to the agent",
                        "interrupt": false
                      },
                      {
                        "action": "wait_agent",
                        "threadIds": ["agent-thread-id"],
                        "timeoutMillis": 1000
                      },
                      {
                        "action": "resume_agent",
                        "threadId": "agent-thread-id"
                      },
                      {
                        "action": "close_agent",
                        "threadId": "agent-thread-id"
                      },
                      {
                        "action": "list_agents",
                        "threadId": "optional-parent-thread-id",
                        "recursive": true
                      }
                    ],
                    "finalAnswer": "final answer when the task is complete"
                  }
                - Return either a non-empty actions array or finalAnswer.
                - Do not return more than %d actions in one step.
                - Actions are executed in the order you provide them.
                - Omit unused fields or set them to null.
                - Prefer SEARCH_FILES to locate code before reading full files.
                - Prefer APPLY_PATCH for targeted edits and WRITE_FILE only for new files or full rewrites.
                - Include editPlan whenever you expect to modify files.
                - Read an existing file before editing it.
                - Use agent delegation when a task is clearer as a focused sub-task than as a local edit batch.
                - Prefer inspection, tests, and small verified edits.
                - Shell commands may be allowed, require approval, or be blocked. If a command is not executed, use the tool result to adapt.
                - Avoid destructive shell commands.
                - Keep all paths relative to the workspace root.
                - After each batch, use the observation from all executed actions before deciding the next step.
                """.formatted(workspaceRoot, maxActionsPerStep);
        if (availableSkills == null || availableSkills.isEmpty()) {
            return basePrompt;
        }
        return basePrompt + System.lineSeparator()
                + """
                Available skills:
                %s

                Skills are selected explicitly by user input, usually with `$skill-name`.
                When a skill is selected, its instructions are injected into the turn context.
                """.formatted(renderAvailableSkills(availableSkills));
    }

    String buildUserPrompt(ReconstructedThreadContext reconstructedContext,
                           String input,
                           String scratchpad,
                           int step,
                           List<ResolvedSkill> selectedSkills,
                           List<String> steeringInputs) {
        String historyBlock = reconstructedContext.recentMessages().stream()
                .map(message -> message.role().name() + ": " + message.content())
                .collect(Collectors.joining(System.lineSeparator()));
        String eventBlock = reconstructedContext.recentActivities().stream()
                .map(activity -> reconstructedContext.threadId().value() + "/" + activity.turnId().value() + " " + renderActivityForPrompt(activity))
                .collect(Collectors.joining(System.lineSeparator()));

        return """
                Planner step number: %d of %d

                Active thread:
                %s

                Recent conversation:
                %s

                Recent turn events:
                %s

                Selected skill instructions:
                %s

                Steering requests since last step:
                %s

                Latest user request:
                %s

                Scratchpad so far:
                %s

                Choose the next tool batch and return JSON only.
                """.formatted(
                step,
                maxSteps,
                reconstructedContext.threadId().value(),
                historyBlock.isBlank() ? "(none)" : historyBlock,
                eventBlock.isBlank() ? "(none)" : eventBlock,
                renderSelectedSkills(selectedSkills),
                steeringInputs == null || steeringInputs.isEmpty() ? "(none)" : String.join(System.lineSeparator(), steeringInputs),
                input,
                scratchpad.isBlank() ? "(none yet)" : scratchpad);
    }

    private String describeActions(List<ToolActionRequest> actions) {
        if (actions.isEmpty()) {
            return "(none)";
        }
        return actions.stream()
                .map(this::describe)
                .collect(Collectors.joining(", "));
    }

    private String describe(ToolActionRequest action) {
        return switch (action.action()) {
            case READ_FILE, WRITE_FILE -> action.action() + " path=" + blankToPlaceholder(action.path());
            case SEARCH_FILES -> action.action()
                    + " query=" + blankToPlaceholder(action.query())
                    + " scope=" + blankToPlaceholder(action.path());
            case APPLY_PATCH -> action.action()
                    + " path=" + blankToPlaceholder(action.path())
                    + " replaceAll=" + action.replaceAll();
            case RUN_COMMAND -> action.action() + " command=" + blankToPlaceholder(action.command());
            case SPAWN_AGENT -> action.action()
                    + " taskName=" + blankToPlaceholder(action.taskName())
                    + " nickname=" + blankToPlaceholder(action.nickname())
                    + " role=" + blankToPlaceholder(action.role());
            case SEND_INPUT -> action.action()
                    + " threadId=" + blankToPlaceholder(action.threadId())
                    + " interrupt=" + action.interrupt();
            case WAIT_AGENT -> action.action()
                    + " threadIds=" + (action.threadIds().isEmpty() ? "(none)" : action.threadIds())
                    + " timeoutMillis=" + (action.timeoutMillis() == null ? "(default)" : action.timeoutMillis());
            case RESUME_AGENT, CLOSE_AGENT -> action.action() + " threadId=" + blankToPlaceholder(action.threadId());
            case LIST_AGENTS -> action.action()
                    + " threadId=" + blankToPlaceholder(action.threadId())
                    + " recursive=" + action.recursive();
        };
    }

    private String describeTarget(ToolActionRequest action) {
        return switch (action.action()) {
            case READ_FILE, WRITE_FILE, APPLY_PATCH -> blankToPlaceholder(action.path());
            case SEARCH_FILES -> "query=" + blankToPlaceholder(action.query()) + ", scope=" + blankToPlaceholder(action.path());
            case RUN_COMMAND -> blankToPlaceholder(action.command());
            case SPAWN_AGENT -> blankToPlaceholder(action.taskName());
            case SEND_INPUT, RESUME_AGENT, CLOSE_AGENT -> blankToPlaceholder(action.threadId());
            case WAIT_AGENT -> action.threadIds().isEmpty()
                    ? "(none)"
                    : action.threadIds().stream().map(ThreadId::value).collect(Collectors.joining(", "));
            case LIST_AGENTS -> blankToPlaceholder(action.threadId());
        };
    }

    private String summarizeToolResult(ToolAction action, String observation) {
        try {
            JsonNode root = objectMapper.readTree(observation);
            if (root.has("success")) {
                StringBuilder summary = new StringBuilder(action.name()).append(" success=").append(root.path("success").asBoolean());
                if (root.has("path") && !root.path("path").asText("").isBlank()) {
                    summary.append(" path=").append(root.path("path").asText());
                }
                if (root.has("scope") && !root.path("scope").asText("").isBlank()) {
                    summary.append(" scope=").append(root.path("scope").asText());
                }
                if (root.has("branch") && !root.path("branch").asText("").isBlank()) {
                    summary.append(" branch=").append(root.path("branch").asText());
                }
                if (root.has("stagedCount")) {
                    summary.append(" staged=").append(root.path("stagedCount").asInt());
                }
                if (root.has("commitHash") && !root.path("commitHash").asText("").isBlank()) {
                    summary.append(" commit=").append(root.path("commitHash").asText(), 0,
                            Math.min(8, root.path("commitHash").asText().length()));
                }
                if (root.has("clean")) {
                    summary.append(" clean=").append(root.path("clean").asBoolean());
                }
                if (root.has("entries") && root.path("entries").isArray()) {
                    summary.append(" entries=").append(root.path("entries").size());
                }
                if (root.has("committedEntries") && root.path("committedEntries").isArray()) {
                    summary.append(" committed=").append(root.path("committedEntries").size());
                }
                if (root.has("target") && !root.path("target").asText("").isBlank()) {
                    summary.append(" target=").append(root.path("target").asText());
                }
                if (root.has("reference") && !root.path("reference").asText("").isBlank()) {
                    summary.append(" reference=").append(root.path("reference").asText());
                }
                if (root.has("totalCharacters")) {
                    summary.append(" chars=").append(root.path("totalCharacters").asInt());
                }
                if (root.has("totalMatches")) {
                    summary.append(" matches=").append(root.path("totalMatches").asInt());
                }
                if (root.has("replacements")) {
                    summary.append(" replacements=").append(root.path("replacements").asInt());
                }
                if (root.has("exitCode")) {
                    summary.append(" exitCode=").append(root.path("exitCode").asInt());
                }
                if (root.has("executed")) {
                    summary.append(" executed=").append(root.path("executed").asBoolean());
                }
                if (root.has("approvalDecision") && !root.path("approvalDecision").asText("").isBlank()) {
                    summary.append(" approval=").append(root.path("approvalDecision").asText());
                }
                if (root.has("threadId") && !root.path("threadId").asText("").isBlank()) {
                    summary.append(" threadId=").append(root.path("threadId").asText());
                }
                if (root.has("turnId") && !root.path("turnId").asText("").isBlank()) {
                    summary.append(" turnId=").append(root.path("turnId").asText());
                }
                if (root.has("parentThreadId") && !root.path("parentThreadId").asText("").isBlank()) {
                    summary.append(" parentThreadId=").append(root.path("parentThreadId").asText());
                }
                if (root.has("agentCount")) {
                    summary.append(" agents=").append(root.path("agentCount").asInt());
                }
                if (root.has("status") && !root.path("status").asText("").isBlank()) {
                    summary.append(" status=").append(root.path("status").asText());
                }
                if (root.has("previousStatus") && !root.path("previousStatus").asText("").isBlank()) {
                    summary.append(" previous=").append(root.path("previousStatus").asText());
                }
                if (root.has("closed")) {
                    summary.append(" closed=").append(root.path("closed").asBoolean());
                }
                if (root.has("finalAnswer") && !root.path("finalAnswer").asText("").isBlank()) {
                    summary.append(" finalAnswer=").append(root.path("finalAnswer").asText());
                }
                if (root.has("timedOut") && root.path("timedOut").asBoolean()) {
                    summary.append(" timedOut=true");
                }
                if (root.has("truncated") && root.path("truncated").asBoolean()) {
                    summary.append(" truncated=true");
                }
                return summary.toString();
            }
        }
        catch (Exception ignored) {
            // Fall through to the generic summary.
        }
        return action.name() + " completed";
    }

    private ActionExecutionOutcome enrichApprovalObservation(ThreadId threadId,
                                                             TurnId turnId,
                                                             ToolActionRequest action,
                                                             String observation,
                                                             List<TurnItem> items,
                                                             Consumer<TurnItem> itemConsumer) {
        if (action != null && action.action() != ToolAction.RUN_COMMAND) {
            return new ActionExecutionOutcome(observation, false, List.of());
        }

        try {
            JsonNode root = objectMapper.readTree(observation);
            if (!(root instanceof ObjectNode objectNode)) {
                return new ActionExecutionOutcome(observation, false, List.of());
            }

            String decision = objectNode.path("approvalDecision").asText("");
            String reason = objectNode.path("approvalReason").asText("");
            boolean executed = objectNode.path("executed").asBoolean(false);
            if (CommandApprovalDecision.REQUIRE_APPROVAL.name().equals(decision) && !executed) {
                CommandApprovalRequest request = commandApprovalService.requestApproval(
                        threadId,
                        turnId,
                        action.command(),
                        objectNode.path("workingDirectory").asText(""),
                        reason);
                objectNode.put("approvalRequestId", request.approvalId().value());
                emitItem(items, itemConsumer, approvalItem(
                        ApprovalState.REQUIRED,
                        request.approvalId().value(),
                        action.command(),
                        "Approval " + shortApprovalId(request.approvalId().value()) + " required for command: " + action.command()));
                return new ActionExecutionOutcome(
                        objectMapper.writeValueAsString(objectNode),
                        true,
                        List.of(request.approvalId().value()));
            }
            if (CommandApprovalDecision.BLOCK.name().equals(decision) && !executed) {
                emitItem(items, itemConsumer, approvalItem(
                        ApprovalState.BLOCKED,
                        "",
                        action.command(),
                        "Command blocked: " + (reason.isBlank() ? "No reason provided." : reason)));
            }
        }
        catch (Exception ignored) {
            // Keep the original observation if enrichment fails.
        }
        return new ActionExecutionOutcome(observation, false, List.of());
    }

    private String shortApprovalId(String approvalId) {
        if (approvalId == null || approvalId.isBlank()) {
            return "(unknown)";
        }
        return approvalId.length() <= 8 ? approvalId : approvalId.substring(0, 8);
    }

    private String stripCodeFences(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            int firstLineBreak = trimmed.indexOf('\n');
            trimmed = firstLineBreak >= 0 ? trimmed.substring(firstLineBreak + 1, trimmed.length() - 3).trim() : trimmed;
        }
        return trimmed;
    }

    private String blankToPlaceholder(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private String safeMessage(String message) {
        return message == null || message.isBlank() ? "Unknown error" : message;
    }

    private String escapeForJson(String value) {
        return safeMessage(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private JsonNode parseObservation(String observation) {
        try {
            return objectMapper.readTree(observation);
        }
        catch (Exception exception) {
            return objectMapper.getNodeFactory().textNode(observation);
        }
    }

    private String createErrorObservation(String error) {
        return "{\"success\":false,\"error\":\"" + escapeForJson(error) + "\"}";
    }

    private String summarizeEditPlan(EditPlan editPlan) {
        if (editPlan == null || editPlan.edits().isEmpty()) {
            return "(none)";
        }
        String summary = blankToPlaceholder(editPlan.summary());
        String edits = editPlan.edits().stream()
                .map(edit -> edit.type() + " " + blankToPlaceholder(edit.path()) + ": " + blankToPlaceholder(edit.description()))
                .collect(Collectors.joining("; "));
        return summary + " | " + edits;
    }

    private void emitItem(List<TurnItem> items, Consumer<TurnItem> itemConsumer, TurnItem item) {
        items.add(item);
        if (itemConsumer != null) {
            itemConsumer.accept(item);
        }
    }

    private String renderActivityForPrompt(ReconstructedTurnActivity activity) {
        return blankToPlaceholder(activity.sourceType()) + ": " + blankToPlaceholder(activity.detail());
    }

    private List<ToolActionRequest> parseActions(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return List.of();
        }

        List<ToolActionRequest> actions = new ArrayList<>();
        JsonNode actionsNode = root.get("actions");
        if (actionsNode != null && actionsNode.isArray()) {
            for (JsonNode actionNode : actionsNode) {
                ToolActionRequest action = parseAction(actionNode);
                if (action != null) {
                    actions.add(action);
                }
            }
            return List.copyOf(actions);
        }

        ToolActionRequest legacyAction = parseAction(root);
        return legacyAction == null ? List.of() : List.of(legacyAction);
    }

    private EditPlan parseEditPlan(JsonNode planNode) {
        if (planNode == null || planNode.isMissingNode() || planNode.isNull()) {
            return null;
        }

        String summary = textValue(planNode.get("summary"));
        JsonNode editsNode = planNode.get("edits");
        if (editsNode == null || !editsNode.isArray()) {
            return summary.isBlank() ? null : new EditPlan(summary, List.of());
        }

        List<PlannedEdit> edits = new ArrayList<>();
        for (JsonNode editNode : editsNode) {
            PlannedEdit edit = parsePlannedEdit(editNode);
            if (edit != null) {
                edits.add(edit);
            }
        }
        return summary.isBlank() && edits.isEmpty() ? null : new EditPlan(summary, edits);
    }

    private PlannedEdit parsePlannedEdit(JsonNode editNode) {
        if (editNode == null || editNode.isMissingNode() || editNode.isNull()) {
            return null;
        }

        String path = textValue(editNode.get("path"));
        String description = firstNonBlank(textValue(editNode.get("description")), textValue(editNode.get("intent")));
        String typeText = textValue(editNode.get("type"));
        PlannedEditType type;
        try {
            type = typeText.isBlank() ? PlannedEditType.MODIFY : PlannedEditType.valueOf(typeText.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException exception) {
            type = PlannedEditType.MODIFY;
        }

        if (path.isBlank() && description.isBlank()) {
            return null;
        }
        return new PlannedEdit(path, type, description);
    }

    private ToolActionRequest parseAction(JsonNode actionNode) {
        if (actionNode == null || actionNode.isMissingNode()) {
            return null;
        }

        String actionText = textValue(actionNode.get("action"));
        if (actionText.isBlank()) {
            return null;
        }

        try {
            return new ToolActionRequest(
                    ToolAction.valueOf(normalizeActionName(actionText)),
                    textValue(actionNode.get("path")),
                    textValue(actionNode.get("query")),
                    textValue(actionNode.get("oldText")),
                    textValue(actionNode.get("newText")),
                    actionNode != null && actionNode.path("replaceAll").asBoolean(false),
                    textValue(actionNode.get("content")),
                    textValue(actionNode.get("command")),
                    textValue(actionNode.get("threadId")),
                    parseThreadIds(actionNode.get("threadIds")),
                    textValue(actionNode.get("taskName")),
                    textValue(actionNode.get("prompt")),
                    textValue(actionNode.get("nickname")),
                    textValue(actionNode.get("role")),
                    parseInteger(actionNode.get("depth")),
                    textValue(actionNode.get("modelProvider")),
                    textValue(actionNode.get("model")),
                    textValue(actionNode.get("cwd")),
                    actionNode != null && actionNode.path("recursive").asBoolean(false),
                    parseLong(actionNode.get("timeoutMillis")),
                    actionNode != null && actionNode.path("interrupt").asBoolean(false)
            );
        }
        catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String validateActions(List<ToolActionRequest> actions) {
        for (int index = 0; index < actions.size(); index++) {
            ToolActionRequest action = actions.get(index);
            int displayIndex = index + 1;
            switch (action.action()) {
                case READ_FILE -> {
                    if (action.path().isBlank()) {
                        return "Action %d (READ_FILE) requires a non-blank path.".formatted(displayIndex);
                    }
                }
                case SEARCH_FILES -> {
                    if (action.query().isBlank()) {
                        return "Action %d (SEARCH_FILES) requires a non-blank query.".formatted(displayIndex);
                    }
                }
                case APPLY_PATCH -> {
                    if (action.path().isBlank()) {
                        return "Action %d (APPLY_PATCH) requires a non-blank path.".formatted(displayIndex);
                    }
                    if (action.oldText().isBlank()) {
                        return "Action %d (APPLY_PATCH) requires oldText.".formatted(displayIndex);
                    }
                }
                case WRITE_FILE -> {
                    if (action.path().isBlank()) {
                        return "Action %d (WRITE_FILE) requires a non-blank path.".formatted(displayIndex);
                    }
                    if (action.content().isBlank()) {
                        return "Action %d (WRITE_FILE) requires content.".formatted(displayIndex);
                    }
                }
                case RUN_COMMAND -> {
                    if (action.command().isBlank()) {
                        return "Action %d (RUN_COMMAND) requires a non-blank command.".formatted(displayIndex);
                    }
                }
                case SPAWN_AGENT -> {
                    if (action.taskName().isBlank()) {
                        return "Action %d (SPAWN_AGENT) requires a non-blank taskName.".formatted(displayIndex);
                    }
                    if (action.depth() != null && action.depth() < 0) {
                        return "Action %d (SPAWN_AGENT) requires depth >= 0 when provided.".formatted(displayIndex);
                    }
                }
                case SEND_INPUT, RESUME_AGENT, CLOSE_AGENT -> {
                    if (action.threadId().isBlank()) {
                        return "Action %d (%s) requires a non-blank threadId.".formatted(displayIndex, action.action());
                    }
                    if (action.action() == ToolAction.SEND_INPUT && action.content().isBlank()) {
                        return "Action %d (SEND_INPUT) requires a non-blank content.".formatted(displayIndex);
                    }
                }
                case WAIT_AGENT -> {
                    if (action.timeoutMillis() != null && action.timeoutMillis() < 1L) {
                        return "Action %d (WAIT_AGENT) requires timeoutMillis >= 1 when provided.".formatted(displayIndex);
                    }
                }
                case LIST_AGENTS -> {
                    // no required fields
                }
            }
        }
        return null;
    }

    private String normalizeActionName(String actionText) {
        return actionText == null ? "" : actionText.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private AgentControl requireAgentControl() {
        AgentControl agentControl = agentControlSupplier.get();
        if (agentControl == null) {
            throw new IllegalStateException("Agent control is unavailable in this runtime.");
        }
        return agentControl;
    }

    private List<ThreadId> parseThreadIds(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            String value = textValue(node);
            return value.isBlank() ? List.of() : List.of(new ThreadId(value));
        }
        List<ThreadId> threadIds = new ArrayList<>();
        for (JsonNode threadIdNode : node) {
            String value = textValue(threadIdNode);
            if (!value.isBlank()) {
                threadIds.add(new ThreadId(value));
            }
        }
        return List.copyOf(threadIds);
    }

    private Integer parseInteger(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isInt() || node.isLong()) {
            return node.intValue();
        }
        String value = textValue(node);
        if (value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    private Long parseLong(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isLong() || node.isInt()) {
            return node.longValue();
        }
        String value = textValue(node);
        if (value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    private String textValue(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("");
    }

    private AgentMessageItem agentMessageItem(String text) {
        return new AgentMessageItem(new ItemId(UUID.randomUUID().toString()), text, Instant.now());
    }

    private PlanItem planItem(EditPlan editPlan) {
        return new PlanItem(new ItemId(UUID.randomUUID().toString()), editPlan, Instant.now());
    }

    private ToolCallItem toolCallItem(String toolName, String target) {
        return new ToolCallItem(new ItemId(UUID.randomUUID().toString()), toolName, target, Instant.now());
    }

    private ToolResultItem toolResultItem(String toolName, String summary) {
        return new ToolResultItem(new ItemId(UUID.randomUUID().toString()), toolName, summary, Instant.now());
    }

    private ApprovalItem approvalItem(ApprovalState state, String approvalId, String command, String detail) {
        return new ApprovalItem(new ItemId(UUID.randomUUID().toString()), state, approvalId, command, detail, Instant.now());
    }

    private RuntimeErrorItem runtimeErrorItem(String message) {
        return new RuntimeErrorItem(new ItemId(UUID.randomUUID().toString()), message, Instant.now());
    }

    enum ToolAction {
        READ_FILE,
        SEARCH_FILES,
        APPLY_PATCH,
        WRITE_FILE,
        RUN_COMMAND,
        SPAWN_AGENT,
        SEND_INPUT,
        WAIT_AGENT,
        RESUME_AGENT,
        CLOSE_AGENT,
        LIST_AGENTS
    }

    record ToolActionRequest(ToolAction action,
                             String path,
                             String query,
                             String oldText,
                             String newText,
                             boolean replaceAll,
                             String content,
                             String command,
                             String threadId,
                             List<ThreadId> threadIds,
                             String taskName,
                             String prompt,
                             String nickname,
                             String role,
                             Integer depth,
                             String modelProvider,
                             String model,
                             String cwd,
                             boolean recursive,
                             Long timeoutMillis,
                             boolean interrupt) {
    }

    record PlannerStep(String summary,
                       EditPlan editPlan,
                       List<ToolActionRequest> actions,
                       String finalAnswer,
                       String validationError) {

        static PlannerStep actions(String summary, EditPlan editPlan, List<ToolActionRequest> actions) {
            return new PlannerStep(summary, editPlan, List.copyOf(actions), null, null);
        }

        static PlannerStep finish(String summary, EditPlan editPlan, String finalAnswer) {
            return new PlannerStep(summary, editPlan, List.of(), finalAnswer, null);
        }

        static PlannerStep invalid(String summary, EditPlan editPlan, List<ToolActionRequest> actions, String validationError) {
            return new PlannerStep(summary, editPlan, List.copyOf(actions), null, validationError);
        }

        boolean isFinished() {
            return finalAnswer != null && !finalAnswer.isBlank();
        }
    }

    private record ExecutionOutcome(TurnStatus status, List<TurnItem> items, String finalAnswer) {
    }

    List<ResolvedSkill> selectedSkillsForInput(String input) {
        return skillService.resolveSkills(input, false);
    }

    private SkillUseItem skillUseItem(List<SkillMetadata> selectedSkills) {
        return new SkillUseItem(new ItemId(UUID.randomUUID().toString()), selectedSkills, Instant.now());
    }

    private String renderAvailableSkills(List<SkillMetadata> availableSkills) {
        return availableSkills.stream()
                .map(skill -> "- %s: %s (file: %s)"
                        .formatted(skill.name(), blankToPlaceholder(skill.shortDescription()), skill.path()))
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private String renderSelectedSkills(List<ResolvedSkill> selectedSkills) {
        if (selectedSkills == null || selectedSkills.isEmpty()) {
            return "(none)";
        }
        return selectedSkills.stream()
                .map(skill -> """
                        Skill: %s
                        Path: %s
                        Instructions:
                        %s
                        """.formatted(
                        skill.metadata().name(),
                        skill.metadata().path(),
                        skill.instructions().trim()))
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private record BatchExecutionOutcome(String observation, boolean awaitingApproval, List<String> approvalIds) {
    }

    private record ActionExecutionOutcome(String observation, boolean awaitingApproval, List<String> approvalIds) {
    }
}
