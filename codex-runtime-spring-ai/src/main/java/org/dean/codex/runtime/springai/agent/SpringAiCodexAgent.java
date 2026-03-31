package org.dean.codex.runtime.springai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dean.codex.core.approval.CommandApprovalService;
import org.dean.codex.core.agent.CodexAgent;
import org.dean.codex.core.agent.TurnControl;
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
import org.dean.codex.protocol.context.ThreadMemory;
import org.dean.codex.protocol.skill.SkillMetadata;
import org.dean.codex.protocol.tool.CommandApprovalDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
    private final ThreadContextReconstructionService threadContextReconstructionService;
    private final SkillService skillService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path workspaceRoot;
    private final int maxSteps;
    private final int maxActionsPerTurn;

    @Autowired
    public SpringAiCodexAgent(ChatClient.Builder chatClientBuilder,
                              FileReaderTool fileReaderTool,
                              FileSearchTool fileSearchTool,
                              FilePatchTool filePatchTool,
                              FileWriterTool fileWriterTool,
                              ShellCommandTool shellCommandTool,
                              CommandApprovalService commandApprovalService,
                              ThreadContextReconstructionService threadContextReconstructionService,
                              SkillService skillService,
                              @Qualifier("codexWorkspaceRoot") Path workspaceRoot,
                              @Value("${codex.agent.max-steps:12}") int maxSteps,
                              @Value("${codex.agent.max-actions-per-turn:3}") int maxActionsPerTurn) {
        this.chatClient = chatClientBuilder.clone()
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
        this.fileReaderTool = fileReaderTool;
        this.fileSearchTool = fileSearchTool;
        this.filePatchTool = filePatchTool;
        this.fileWriterTool = fileWriterTool;
        this.shellCommandTool = shellCommandTool;
        this.commandApprovalService = commandApprovalService;
        this.threadContextReconstructionService = threadContextReconstructionService;
        this.skillService = skillService;
        this.workspaceRoot = workspaceRoot;
        this.maxSteps = Math.max(1, maxSteps);
        this.maxActionsPerTurn = Math.max(1, maxActionsPerTurn);
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

        for (int step = 1; step <= maxSteps; step++) {
            if (turnControl.interruptionRequested()) {
                return interruptedOutcome(items, itemConsumer);
            }
            List<String> steeringInputs = turnControl.drainSteeringInputs();
            if (!steeringInputs.isEmpty()) {
                steeringInputs.forEach(steeringInput ->
                        emitItem(items, itemConsumer, new UserMessageItem(new ItemId(UUID.randomUUID().toString()), steeringInput, Instant.now())));
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

    private PlannerStep requestDecision(ThreadId threadId,
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
            if (actions.size() > maxActionsPerTurn) {
                return PlannerStep.invalid(summary, editPlan, actions,
                        "The model selected %d actions, exceeding the configured limit of %d."
                                .formatted(actions.size(), maxActionsPerTurn));
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
                - Prefer inspection, tests, and small verified edits.
                - Shell commands may be allowed, require approval, or be blocked. If a command is not executed, use the tool result to adapt.
                - Avoid destructive shell commands.
                - Keep all paths relative to the workspace root.
                - After each batch, use the observation from all executed actions before deciding the next step.
                """.formatted(workspaceRoot, maxActionsPerTurn);
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

                Compacted thread memory:
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
                renderThreadMemory(reconstructedContext.threadMemory()),
                renderSelectedSkills(selectedSkills),
                steeringInputs == null || steeringInputs.isEmpty() ? "(none)" : String.join(System.lineSeparator(), steeringInputs),
                input,
                scratchpad.isBlank() ? "(none yet)" : scratchpad);
    }

    private String renderThreadMemory(ThreadMemory threadMemory) {
        if (threadMemory == null || threadMemory.summary() == null || threadMemory.summary().isBlank()) {
            return "(none)";
        }
        return "Memory " + threadMemory.memoryId() + " (" + threadMemory.compactedTurnCount() + " turns):"
                + System.lineSeparator()
                + threadMemory.summary();
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
        };
    }

    private String describeTarget(ToolActionRequest action) {
        return switch (action.action()) {
            case READ_FILE, WRITE_FILE, APPLY_PATCH -> blankToPlaceholder(action.path());
            case SEARCH_FILES -> "query=" + blankToPlaceholder(action.query()) + ", scope=" + blankToPlaceholder(action.path());
            case RUN_COMMAND -> blankToPlaceholder(action.command());
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
                    ToolAction.valueOf(actionText.trim().toUpperCase(Locale.ROOT)),
                    textValue(actionNode.get("path")),
                    textValue(actionNode.get("query")),
                    textValue(actionNode.get("oldText")),
                    textValue(actionNode.get("newText")),
                    actionNode != null && actionNode.path("replaceAll").asBoolean(false),
                    textValue(actionNode.get("content")),
                    textValue(actionNode.get("command"))
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
            }
        }
        return null;
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
        RUN_COMMAND
    }

    record ToolActionRequest(ToolAction action,
                             String path,
                             String query,
                             String oldText,
                             String newText,
                             boolean replaceAll,
                             String content,
                             String command) {
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
