package ai.copilot;

import ai.copilot.tools.FileReaderTool;
import ai.copilot.tools.FileWriterTool;
import ai.copilot.tools.JavaProjectAnalyzerTool;
import ai.copilot.tools.ShellCommandTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.stream.Collectors;

@Component
public class CopilotAgent implements CopilotAgentHandler {

    private static final Logger logger = LoggerFactory.getLogger(CopilotAgent.class);
    private static final int MAX_HISTORY_ENTRIES = 8;

    private final ChatClient chatClient;
    private final Object[] toolBeans;
    private final Path workspaceRoot;
    private final Deque<String> conversationHistory = new ArrayDeque<>();

    @Autowired
    public CopilotAgent(ChatClient.Builder chatClientBuilder,
                        FileReaderTool fileReaderTool,
                        FileWriterTool fileWriterTool,
                        ShellCommandTool shellCommandTool,
                        JavaProjectAnalyzerTool javaProjectAnalyzerTool,
                        @Qualifier("copilotWorkspaceRoot") Path workspaceRoot) {
        this.chatClient = chatClientBuilder.clone()
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
        this.toolBeans = new Object[]{fileReaderTool, fileWriterTool, shellCommandTool, javaProjectAnalyzerTool};
        this.workspaceRoot = workspaceRoot;
    }

    @Override
    public String mode() {
        return "direct";
    }

    @Override
    public synchronized String handleUserInput(String input) {
        try {
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(input);
            logger.debug("LLM interaction [{}] request\nSystem:\n{}\nUser:\n{}", mode(), systemPrompt, userPrompt);

            String response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .tools(toolBeans)
                    .call()
                    .content();
            logger.debug("LLM interaction [{}] response\n{}", mode(), response == null ? "(null)" : response);

            String safeResponse = response == null || response.isBlank()
                    ? "I couldn't produce a response for that request."
                    : response;

            rememberTurn("User", input);
            rememberTurn("Assistant", safeResponse);
            return safeResponse;
        }
        catch (Exception exception) {
            logger.debug("LLM interaction [{}] failed for input: {}", mode(), input, exception);
            return "The copilot agent hit an error: " + exception.getMessage();
        }
    }

    private String buildSystemPrompt() {
        return """
                You are a software engineering copilot running inside a local workspace.
                Workspace root: %s

                Rules:
                - Use the available tools whenever the user asks about files, code, command output, or compiled Java structure.
                - Do not pretend you read or changed a file unless you actually used a tool.
                - All file paths must be relative to the workspace root unless a tool explicitly requires an absolute path.
                - Read an existing file before proposing or making edits to it.
                - Use shell commands for safe inspection, build, and test steps.
                - Use the Java project analyzer when the user wants a package/class/member summary for compiled classes or a jar.
                - Prefer small, verifiable changes and mention what you changed and verified.
                - If a tool reports an error, explain it clearly and suggest the next step.
                """.formatted(workspaceRoot);
    }

    private String buildUserPrompt(String input) {
        if (conversationHistory.isEmpty()) {
            return input;
        }

        String historyBlock = conversationHistory.stream().collect(Collectors.joining(System.lineSeparator()));
        return """
                Recent conversation context:
                %s

                Latest user request:
                %s
                """.formatted(historyBlock, input);
    }

    private void rememberTurn(String role, String content) {
        conversationHistory.addLast(role + ": " + content.replaceAll("\\s+", " ").trim());
//        while (conversationHistory.size() > MAX_HISTORY_ENTRIES) {
//            conversationHistory.removeFirst();
//        }
    }
}
