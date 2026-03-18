package ai.copilot;

import ai.copilot.tools.FileReaderTool;
import ai.copilot.tools.FileWriterTool;
import ai.copilot.tools.JavaProjectAnalyzerTool;
import ai.copilot.tools.ShellCommandTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ReActCopilotAgent implements CopilotAgentHandler {

    private static final Logger logger = LoggerFactory.getLogger(ReActCopilotAgent.class);
    private static final int MAX_HISTORY_ENTRIES = 8;

    private final ChatClient chatClient;
    private final FileReaderTool fileReaderTool;
    private final FileWriterTool fileWriterTool;
    private final ShellCommandTool shellCommandTool;
    private final JavaProjectAnalyzerTool javaProjectAnalyzerTool;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path workspaceRoot;
    private final int maxSteps;
    private final int maxActionsPerStep;
    private final Deque<String> conversationHistory = new ArrayDeque<>();

    @Autowired
    public ReActCopilotAgent(ChatClient.Builder chatClientBuilder,
                             FileReaderTool fileReaderTool,
                             FileWriterTool fileWriterTool,
                             ShellCommandTool shellCommandTool,
                             JavaProjectAnalyzerTool javaProjectAnalyzerTool,
                             @Qualifier("copilotWorkspaceRoot") Path workspaceRoot,
                             @Value("${copilot.react.max-steps:10}") int maxSteps,
                             @Value("${copilot.react.max-actions-per-step:3}") int maxActionsPerStep) {
        this.chatClient = chatClientBuilder.clone()
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
        this.fileReaderTool = fileReaderTool;
        this.fileWriterTool = fileWriterTool;
        this.shellCommandTool = shellCommandTool;
        this.javaProjectAnalyzerTool = javaProjectAnalyzerTool;
        this.workspaceRoot = workspaceRoot;
        this.maxSteps = Math.max(1, maxSteps);
        this.maxActionsPerStep = Math.max(1, maxActionsPerStep);
    }

    @Override
    public String mode() {
        return "react";
    }

    @Override
    public synchronized String handleUserInput(String input) {
        try {
            String answer = runReActLoop(input);
            String safeAnswer = answer == null || answer.isBlank()
                    ? "I couldn't produce a response for that request."
                    : answer;
            rememberTurn("User", input);
            rememberTurn("Assistant", safeAnswer);
            return safeAnswer;
        }
        catch (Exception exception) {
            logger.debug("LLM interaction [{}] failed for input: {}", mode(), input, exception);
            return "The ReAct copilot agent hit an error: " + exception.getMessage();
        }
    }

    private String runReActLoop(String input) {
        StringBuilder scratchpad = new StringBuilder();
        String lastObservation = "(none)";

        for (int step = 1; step <= maxSteps; step++) {
            ReActStep decision = requestDecision(input, scratchpad.toString(), step);
            if (decision.isFinished()) {
                return decision.finalAnswer() == null || decision.finalAnswer().isBlank()
                        ? "I have finished the task, but the model did not provide a final answer."
                        : decision.finalAnswer();
            }

            String observation = decision.validationError() == null
                    ? executeActions(decision.actions())
                    : createErrorObservation(decision.validationError());
            lastObservation = observation;
            scratchpad.append("Step ").append(step).append(':').append(System.lineSeparator())
                    .append("Thought: ").append(blankToPlaceholder(decision.thought())).append(System.lineSeparator())
                    .append("Actions: ").append(describeActions(decision.actions())).append(System.lineSeparator())
                    .append("Observation: ").append(observation).append(System.lineSeparator()).append(System.lineSeparator());
        }

        return "I stopped after %d ReAct steps without reaching a final answer. Last observation:%n%s"
                .formatted(maxSteps, lastObservation);
    }

    private ReActStep requestDecision(String input, String scratchpad, int step) {
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(input, scratchpad, step);
        logger.debug("LLM interaction [{} step {}] request\nSystem:\n{}\nUser:\n{}", mode(), step, systemPrompt, userPrompt);

        String response = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
        logger.debug("LLM interaction [{} step {}] response\n{}", mode(), step, response == null ? "(null)" : response);

        ReActStep decision = parseDecision(response);
        logger.debug("LLM interaction [{} step {}] parsed actions={} finalAnswerPresent={} thought={}",
                mode(), step, decision.actions().size(), decision.isFinished(), blankToPlaceholder(decision.thought()));
        return decision;
    }

    String executeActions(List<ReActActionRequest> actions) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (int index = 0; index < actions.size(); index++) {
            ReActActionRequest action = actions.get(index);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", index + 1);
            entry.put("action", action.action());
            entry.put("target", action.action() == ReActAction.RUN_COMMAND
                    ? blankToPlaceholder(action.command())
                    : blankToPlaceholder(action.path()));
            entry.put("result", parseObservation(executeAction(action)));
            results.add(entry);
        }

        try {
            return objectMapper.writeValueAsString(Map.of("results", results));
        }
        catch (Exception exception) {
            return createErrorObservation(exception.getMessage());
        }
    }

    String executeAction(ReActActionRequest action) {
        try {
            return switch (action.action()) {
                case READ_FILE -> objectMapper.writeValueAsString(fileReaderTool.readFile(action.path()));
                case WRITE_FILE -> objectMapper.writeValueAsString(fileWriterTool.writeFile(action.path(), action.content()));
                case RUN_COMMAND -> objectMapper.writeValueAsString(shellCommandTool.runCommand(action.command()));
                case ANALYZE_JAVA_PROJECT -> objectMapper.writeValueAsString(Map.of("summary", javaProjectAnalyzerTool.analyzeProject(action.path())));
            };
        }
        catch (Exception exception) {
            return createErrorObservation(exception.getMessage());
        }
    }

    ReActStep parseDecision(String response) {
        try {
            String cleaned = stripCodeFences(response);
            JsonNode root = objectMapper.readTree(cleaned);
            String thought = textValue(root.get("thought"));
            String finalAnswer = textValue(root.get("finalAnswer"));
            List<ReActActionRequest> actions = parseActions(root);

            if (!finalAnswer.isBlank()) {
                if (!actions.isEmpty()) {
                    return ReActStep.invalid(thought, actions,
                            "Return either actions or finalAnswer, but not both in the same ReAct step.");
                }
                return ReActStep.finish(thought, finalAnswer);
            }

            if (actions.isEmpty()) {
                return ReActStep.invalid(thought, List.of(),
                        "I could not determine any ReAct actions from the model response.");
            }
            if (actions.size() > maxActionsPerStep) {
                return ReActStep.invalid(thought, actions,
                        "The model selected %d actions, exceeding the configured limit of %d."
                                .formatted(actions.size(), maxActionsPerStep));
            }

            String validationError = validateActions(actions);
            return validationError == null
                    ? ReActStep.actions(thought, actions)
                    : ReActStep.invalid(thought, actions, validationError);
        }
        catch (Exception exception) {
            String fallbackMessage = response == null || response.isBlank()
                    ? "I couldn't parse a valid ReAct step from the model response."
                    : response.trim();
            return ReActStep.invalid("The model returned a non-JSON response.", List.of(),
                    "Invalid ReAct JSON response: " + fallbackMessage);
        }
    }

    private String buildSystemPrompt() {
        return """
                You are a software engineering copilot using the ReAct pattern.
                Workspace root: %s

                Your job is to reason step by step and choose the next batch of actions to execute.
                Available actions:
                - READ_FILE: read a file relative to the workspace root
                - WRITE_FILE: create or overwrite a file relative to the workspace root
                - RUN_COMMAND: run a zsh command from the workspace root
                - ANALYZE_JAVA_PROJECT: summarize packages, classes, public fields, and public methods from the current project's compiled classes or from an absolute jar/classes path

                Rules:
                - Return JSON only. Do not wrap it in prose.
                - Use this schema exactly:
                  {
                    "thought": "short reasoning for the next step",
                    "actions": [
                      {
                        "action": "READ_FILE | WRITE_FILE | RUN_COMMAND | ANALYZE_JAVA_PROJECT",
                        "path": "relative/path/when-needed",
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
                - Read an existing file before editing it.
                - Use ANALYZE_JAVA_PROJECT with a blank path to inspect the current project's target/classes directory.
                - If ANALYZE_JAVA_PROJECT is used with a path, that path must be absolute.
                - Keep paths relative to the workspace root unless ANALYZE_JAVA_PROJECT is inspecting an external jar or classes directory.
                - After each batch, use the observation from all executed actions before deciding the next step.
                - When the task is complete, leave actions empty or omitted and return finalAnswer.
                """.formatted(workspaceRoot, maxActionsPerStep);
    }

    private String buildUserPrompt(String input, String scratchpad, int step) {
        String historyBlock = conversationHistory.isEmpty()
                ? "(none)"
                : conversationHistory.stream().collect(Collectors.joining(System.lineSeparator()));

        return """
                ReAct step number: %d of %d

                Recent conversation:
                %s

                Current user request:
                %s

                ReAct scratchpad so far:
                %s

                Choose the next action and return JSON only.
                """.formatted(step, maxSteps, historyBlock, input, scratchpad.isBlank() ? "(none yet)" : scratchpad);
    }

    private String describeActions(List<ReActActionRequest> actions) {
        if (actions.isEmpty()) {
            return "(none)";
        }
        return actions.stream()
                .map(this::describe)
                .collect(Collectors.joining(", "));
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

    private String escapeForJson(String value) {
        return value == null ? "Unknown error" : value.replace("\\", "\\\\").replace("\"", "\\\"");
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

    private List<ReActActionRequest> parseActions(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return List.of();
        }

        List<ReActActionRequest> actions = new ArrayList<>();
        JsonNode actionsNode = root.get("actions");
        if (actionsNode != null && actionsNode.isArray()) {
            for (JsonNode actionNode : actionsNode) {
                ReActActionRequest action = parseAction(actionNode);
                if (action != null) {
                    actions.add(action);
                }
            }
            return List.copyOf(actions);
        }

        ReActActionRequest legacyAction = parseAction(root);
        return legacyAction == null ? List.of() : List.of(legacyAction);
    }

    private ReActActionRequest parseAction(JsonNode actionNode) {
        if (actionNode == null || actionNode.isMissingNode()) {
            return null;
        }

        String actionText = textValue(actionNode.get("action"));
        if (actionText.isBlank()) {
            return null;
        }

        try {
            return new ReActActionRequest(
                    ReActAction.valueOf(actionText),
                    textValue(actionNode.get("path")),
                    textValue(actionNode.get("content")),
                    textValue(actionNode.get("command"))
            );
        }
        catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String validateActions(List<ReActActionRequest> actions) {
        for (int index = 0; index < actions.size(); index++) {
            ReActActionRequest action = actions.get(index);
            int displayIndex = index + 1;
            switch (action.action()) {
                case READ_FILE -> {
                    if (action.path().isBlank()) {
                        return "Action %d (READ_FILE) requires a non-blank path.".formatted(displayIndex);
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
                case ANALYZE_JAVA_PROJECT -> {
                    // Blank path is allowed to inspect the current project's target/classes directory.
                }
            }
        }
        return null;
    }

    private String describe(ReActActionRequest action) {
        return switch (action.action()) {
            case READ_FILE, WRITE_FILE -> action.action() + " path=" + blankToPlaceholder(action.path());
            case RUN_COMMAND -> action.action() + " command=" + blankToPlaceholder(action.command());
            case ANALYZE_JAVA_PROJECT -> action.action() + " path=" + blankToPlaceholder(action.path());
        };
    }

    private String textValue(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("");
    }

    private void rememberTurn(String role, String content) {
        conversationHistory.addLast(role + ": " + content.replaceAll("\\s+", " ").trim());
        while (conversationHistory.size() > MAX_HISTORY_ENTRIES) {
            conversationHistory.removeFirst();
        }
    }

    enum ReActAction {
        READ_FILE,
        WRITE_FILE,
        RUN_COMMAND,
        ANALYZE_JAVA_PROJECT
    }

    record ReActActionRequest(ReActAction action,
                              String path,
                              String content,
                              String command) {
    }

    record ReActStep(String thought,
                     List<ReActActionRequest> actions,
                     String finalAnswer,
                     String validationError) {

        static ReActStep actions(String thought, List<ReActActionRequest> actions) {
            return new ReActStep(thought, List.copyOf(actions), null, null);
        }

        static ReActStep finish(String thought, String finalAnswer) {
            return new ReActStep(thought, List.of(), finalAnswer, null);
        }

        static ReActStep invalid(String thought, List<ReActActionRequest> actions, String validationError) {
            return new ReActStep(thought, List.copyOf(actions), null, validationError);
        }

        boolean isFinished() {
            return finalAnswer != null && !finalAnswer.isBlank();
        }
    }
}
