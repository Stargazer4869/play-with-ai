package org.dean.codex.runtime.springai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dean.codex.core.approval.CommandApprovalService;
import org.dean.codex.core.agent.CodexAgent;
import org.dean.codex.core.conversation.ConversationStore;
import org.dean.codex.core.tool.local.FilePatchTool;
import org.dean.codex.core.tool.local.FileReaderTool;
import org.dean.codex.core.tool.local.FileSearchTool;
import org.dean.codex.core.tool.local.FileWriterTool;
import org.dean.codex.core.tool.local.ShellCommandTool;
import org.dean.codex.protocol.conversation.ItemId;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.event.CodexTurnResult;
import org.dean.codex.protocol.event.TurnEvent;
import org.dean.codex.protocol.approval.CommandApprovalRequest;
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
import java.util.Map;
import java.util.UUID;
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
    private final ConversationStore conversationStore;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path workspaceRoot;
    private final int maxSteps;
    private final int maxActionsPerTurn;
    private final int historyWindow;

    @Autowired
    public SpringAiCodexAgent(ChatClient.Builder chatClientBuilder,
                              FileReaderTool fileReaderTool,
                              FileSearchTool fileSearchTool,
                              FilePatchTool filePatchTool,
                              FileWriterTool fileWriterTool,
                              ShellCommandTool shellCommandTool,
                              CommandApprovalService commandApprovalService,
                              ConversationStore conversationStore,
                              @Qualifier("codexWorkspaceRoot") Path workspaceRoot,
                              @Value("${codex.agent.max-steps:12}") int maxSteps,
                              @Value("${codex.agent.max-actions-per-turn:3}") int maxActionsPerTurn,
                              @Value("${codex.agent.history-window:8}") int historyWindow) {
        this.chatClient = chatClientBuilder.clone()
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
        this.fileReaderTool = fileReaderTool;
        this.fileSearchTool = fileSearchTool;
        this.filePatchTool = filePatchTool;
        this.fileWriterTool = fileWriterTool;
        this.shellCommandTool = shellCommandTool;
        this.commandApprovalService = commandApprovalService;
        this.conversationStore = conversationStore;
        this.workspaceRoot = workspaceRoot;
        this.maxSteps = Math.max(1, maxSteps);
        this.maxActionsPerTurn = Math.max(1, maxActionsPerTurn);
        this.historyWindow = Math.max(1, historyWindow);
    }

    @Override
    public synchronized CodexTurnResult handleTurn(ThreadId threadId, TurnId turnId, String input) {
        String safeInput = input == null ? "" : input.trim();
        try {
            ExecutionOutcome outcome = runPlanningLoop(threadId, turnId, safeInput);
            String finalAnswer = outcome.finalAnswer() == null || outcome.finalAnswer().isBlank()
                    ? "I couldn't produce a response for that request."
                    : outcome.finalAnswer();
            return new CodexTurnResult(threadId, turnId, outcome.status(), outcome.events(), finalAnswer);
        }
        catch (Exception exception) {
            logger.debug("Codex turn failed for thread {} with input: {}", threadId.value(), safeInput, exception);
            TurnEvent errorEvent = turnEvent("runtime.error", safeMessage(exception.getMessage()));
            return new CodexTurnResult(
                    threadId,
                    turnId,
                    TurnStatus.FAILED,
                    List.of(errorEvent),
                    "The Codex agent hit an error: " + safeMessage(exception.getMessage()));
        }
    }

    private ExecutionOutcome runPlanningLoop(ThreadId threadId, TurnId turnId, String input) {
        List<TurnEvent> events = new ArrayList<>();
        StringBuilder scratchpad = new StringBuilder();
        String lastObservation = "(none)";

        for (int step = 1; step <= maxSteps; step++) {
            PlannerStep decision = requestDecision(threadId, input, scratchpad.toString(), step);
            if (decision.isFinished()) {
                return new ExecutionOutcome(
                        TurnStatus.COMPLETED,
                        List.copyOf(events),
                        decision.finalAnswer() == null || decision.finalAnswer().isBlank()
                                ? "I have finished the task, but the model did not provide a final answer."
                                : decision.finalAnswer());
            }

            String observation;
            BatchExecutionOutcome batchOutcome = null;
            if (decision.validationError() == null) {
                batchOutcome = executeActions(threadId, turnId, decision.actions(), events);
                observation = batchOutcome.observation();
            }
            else {
                observation = createErrorObservation(decision.validationError());
                events.add(turnEvent("runtime.validation", decision.validationError()));
            }

            lastObservation = observation;
            scratchpad.append("Step ").append(step).append(':').append(System.lineSeparator())
                    .append("Summary: ").append(blankToPlaceholder(decision.summary())).append(System.lineSeparator())
                    .append("Actions: ").append(describeActions(decision.actions())).append(System.lineSeparator())
                    .append("Observation: ").append(observation).append(System.lineSeparator()).append(System.lineSeparator());

            if (batchOutcome != null && batchOutcome.awaitingApproval()) {
                String approvalList = batchOutcome.approvalIds().isEmpty()
                        ? "(unknown approval id)"
                        : batchOutcome.approvalIds().stream()
                        .map(this::shortApprovalId)
                        .collect(Collectors.joining(", "));
                return new ExecutionOutcome(
                        TurnStatus.AWAITING_APPROVAL,
                        List.copyOf(events),
                        "Approval required for command execution. Review with :approvals and continue with :approve <id-prefix> or :reject <id-prefix>. Pending approval ids: " + approvalList);
            }
        }

        events.add(turnEvent("runtime.incomplete", "Stopped after " + maxSteps + " planner steps."));
        return new ExecutionOutcome(
                TurnStatus.COMPLETED,
                List.copyOf(events),
                "I stopped after %d planner steps without reaching a final answer. Last observation:%n%s"
                        .formatted(maxSteps, lastObservation));
    }

    private PlannerStep requestDecision(ThreadId threadId, String input, String scratchpad, int step) {
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(threadId, input, scratchpad, step);
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

    BatchExecutionOutcome executeActions(ThreadId threadId, TurnId turnId, List<ToolActionRequest> actions, List<TurnEvent> events) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> approvalIds = new ArrayList<>();
        boolean awaitingApproval = false;
        for (int index = 0; index < actions.size(); index++) {
            ToolActionRequest action = actions.get(index);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", index + 1);
            entry.put("action", action.action());
            entry.put("target", describeTarget(action));
            ActionExecutionOutcome actionOutcome = executeAction(threadId, turnId, action, events);
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

    ActionExecutionOutcome executeAction(ThreadId threadId, TurnId turnId, ToolActionRequest action, List<TurnEvent> events) {
        events.add(turnEvent("tool.call", describe(action)));
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
        ActionExecutionOutcome outcome = enrichApprovalObservation(threadId, turnId, action, observation, events);
        observation = outcome.observation();
        events.add(turnEvent("tool.result", summarizeToolResult(action.action(), observation)));
        return outcome;
    }

    PlannerStep parseDecision(String response) {
        try {
            String cleaned = stripCodeFences(response);
            JsonNode root = objectMapper.readTree(cleaned);
            String summary = firstNonBlank(textValue(root.get("summary")), textValue(root.get("thought")));
            String finalAnswer = textValue(root.get("finalAnswer"));
            List<ToolActionRequest> actions = parseActions(root);

            if (!finalAnswer.isBlank()) {
                if (!actions.isEmpty()) {
                    return PlannerStep.invalid(summary, actions,
                            "Return either actions or finalAnswer, but not both in the same planner step.");
                }
                return PlannerStep.finish(summary, finalAnswer);
            }

            if (actions.isEmpty()) {
                return PlannerStep.invalid(summary, List.of(),
                        "I could not determine any tool actions from the model response.");
            }
            if (actions.size() > maxActionsPerTurn) {
                return PlannerStep.invalid(summary, actions,
                        "The model selected %d actions, exceeding the configured limit of %d."
                                .formatted(actions.size(), maxActionsPerTurn));
            }

            String validationError = validateActions(actions);
            return validationError == null
                    ? PlannerStep.actions(summary, actions)
                    : PlannerStep.invalid(summary, actions, validationError);
        }
        catch (Exception exception) {
            String fallbackMessage = response == null || response.isBlank()
                    ? "I couldn't parse a valid planner step from the model response."
                    : response.trim();
            return PlannerStep.invalid("The model returned a non-JSON response.", List.of(),
                    "Invalid planner JSON response: " + fallbackMessage);
        }
    }

    private String buildSystemPrompt() {
        return """
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
                - Read an existing file before editing it.
                - Prefer inspection, tests, and small verified edits.
                - Shell commands may be allowed, require approval, or be blocked. If a command is not executed, use the tool result to adapt.
                - Avoid destructive shell commands.
                - Keep all paths relative to the workspace root.
                - After each batch, use the observation from all executed actions before deciding the next step.
                """.formatted(workspaceRoot, maxActionsPerTurn);
    }

    private String buildUserPrompt(ThreadId threadId, String input, String scratchpad, int step) {
        List<org.dean.codex.protocol.conversation.ConversationMessage> messages = conversationStore.messages(threadId);
        List<org.dean.codex.protocol.conversation.ConversationTurn> turns = conversationStore.turns(threadId);
        int fromIndex = Math.max(0, messages.size() - historyWindow);
        int turnFromIndex = Math.max(0, turns.size() - historyWindow);
        String historyBlock = messages.subList(fromIndex, messages.size()).stream()
                .map(message -> message.role().name() + ": " + message.content())
                .collect(Collectors.joining(System.lineSeparator()));
        String eventBlock = turns.subList(turnFromIndex, turns.size()).stream()
                .flatMap(turn -> turn.events().stream()
                        .map(event -> turn.turnId().value() + " " + event.type() + ": " + event.detail()))
                .collect(Collectors.joining(System.lineSeparator()));

        return """
                Planner step number: %d of %d

                Active thread:
                %s

                Recent conversation:
                %s

                Recent turn events:
                %s

                Latest user request:
                %s

                Scratchpad so far:
                %s

                Choose the next tool batch and return JSON only.
                """.formatted(
                step,
                maxSteps,
                threadId.value(),
                historyBlock.isBlank() ? "(none)" : historyBlock,
                eventBlock.isBlank() ? "(none)" : eventBlock,
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
                                                             List<TurnEvent> events) {
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
                events.add(turnEvent("approval.required",
                        "Approval " + shortApprovalId(request.approvalId().value())
                                + " required for command: " + action.command()));
                return new ActionExecutionOutcome(
                        objectMapper.writeValueAsString(objectNode),
                        true,
                        List.of(request.approvalId().value()));
            }
            if (CommandApprovalDecision.BLOCK.name().equals(decision) && !executed) {
                events.add(turnEvent("approval.blocked",
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
                    ToolAction.valueOf(actionText),
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

    private TurnEvent turnEvent(String type, String detail) {
        return new TurnEvent(new ItemId(UUID.randomUUID().toString()), type, detail, Instant.now());
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
                       List<ToolActionRequest> actions,
                       String finalAnswer,
                       String validationError) {

        static PlannerStep actions(String summary, List<ToolActionRequest> actions) {
            return new PlannerStep(summary, List.copyOf(actions), null, null);
        }

        static PlannerStep finish(String summary, String finalAnswer) {
            return new PlannerStep(summary, List.of(), finalAnswer, null);
        }

        static PlannerStep invalid(String summary, List<ToolActionRequest> actions, String validationError) {
            return new PlannerStep(summary, List.copyOf(actions), null, validationError);
        }

        boolean isFinished() {
            return finalAnswer != null && !finalAnswer.isBlank();
        }
    }

    private record ExecutionOutcome(TurnStatus status, List<TurnEvent> events, String finalAnswer) {
    }

    private record BatchExecutionOutcome(String observation, boolean awaitingApproval, List<String> approvalIds) {
    }

    private record ActionExecutionOutcome(String observation, boolean awaitingApproval, List<String> approvalIds) {
    }
}
