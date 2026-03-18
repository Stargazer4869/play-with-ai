package ai.copilot;

import ai.copilot.tools.FileReaderTool;
import ai.copilot.tools.FileWriterTool;
import ai.copilot.tools.JavaProjectAnalyzerTool;
import ai.copilot.tools.ShellCommandTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.template.TemplateRenderer;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.core.io.Resource;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReActCopilotAgentTest {

    private ReActCopilotAgent agent;

    @BeforeEach
    void setUp() {
        agent = new ReActCopilotAgent(
                new NoOpChatClientBuilder(),
                path -> new FileReaderTool.FileReadResult(true, path, "", false, 0, ""),
                (path, content) -> new FileWriterTool.FileWriteResult(true, path, true, content == null ? 0 : content.length(), ""),
                command -> new ShellCommandTool.CommandResult(true, command, 0, "", "", false, "/tmp/workspace", ""),
                location -> "summary for " + location,
                Path.of("/tmp/workspace"),
                6,
                2
        );
    }

    @Test
    void parseDecisionSupportsMultipleActions() {
        ReActCopilotAgent.ReActStep step = agent.parseDecision("""
                {
                  "thought": "Inspect the project and then run tests",
                  "actions": [
                    {
                      "action": "READ_FILE",
                      "path": "pom.xml"
                    },
                    {
                      "action": "RUN_COMMAND",
                      "command": "mvn test"
                    }
                  ]
                }
                """);

        assertFalse(step.isFinished());
        assertNull(step.validationError());
        assertEquals(2, step.actions().size());
        assertEquals(ReActCopilotAgent.ReActAction.READ_FILE, step.actions().get(0).action());
        assertEquals("pom.xml", step.actions().get(0).path());
        assertEquals(ReActCopilotAgent.ReActAction.RUN_COMMAND, step.actions().get(1).action());
        assertEquals("mvn test", step.actions().get(1).command());
    }

    @Test
    void parseDecisionRejectsMoreThanConfiguredMaxActions() {
        ReActCopilotAgent.ReActStep step = agent.parseDecision("""
                {
                  "thought": "Try too many actions",
                  "actions": [
                    {"action": "READ_FILE", "path": "pom.xml"},
                    {"action": "READ_FILE", "path": "README.md"},
                    {"action": "RUN_COMMAND", "command": "mvn test"}
                  ]
                }
                """);

        assertFalse(step.isFinished());
        assertEquals(3, step.actions().size());
        assertTrue(step.validationError().contains("configured limit of 2"));
    }

    @Test
    void parseDecisionStillAcceptsLegacySingleActionShape() {
        ReActCopilotAgent.ReActStep step = agent.parseDecision("""
                {
                  "thought": "Read one file",
                  "action": "READ_FILE",
                  "path": "README.md"
                }
                """);

        assertFalse(step.isFinished());
        assertNull(step.validationError());
        assertEquals(List.of(ReActCopilotAgent.ReActAction.READ_FILE),
                step.actions().stream().map(ReActCopilotAgent.ReActActionRequest::action).toList());
        assertEquals("README.md", step.actions().get(0).path());
    }

    @Test
    void parseDecisionTreatsFinalAnswerAsFinishedStep() {
        ReActCopilotAgent.ReActStep step = agent.parseDecision("""
                {
                  "thought": "Task is done",
                  "finalAnswer": "All set"
                }
                """);

        assertTrue(step.isFinished());
        assertEquals("All set", step.finalAnswer());
        assertTrue(step.actions().isEmpty());
        assertNull(step.validationError());
    }

    private static final class NoOpChatClientBuilder implements ChatClient.Builder {

        @Override
        public ChatClient.Builder defaultAdvisors(org.springframework.ai.chat.client.advisor.api.Advisor... advisors) {
            return this;
        }

        @Override
        public ChatClient.Builder defaultAdvisors(Consumer<ChatClient.AdvisorSpec> advisorSpecConsumer) {
            return this;
        }

        @Override
        public ChatClient.Builder defaultAdvisors(List<org.springframework.ai.chat.client.advisor.api.Advisor> advisors) {
            return this;
        }

        @Override
        public ChatClient.Builder defaultOptions(ChatOptions chatOptions) {
            return this;
        }

        @Override
        public ChatClient.Builder defaultUser(String text) {
            return this;
        }

        @Override
        public ChatClient.Builder defaultUser(Resource resource, Charset charset) {
            return this;
        }

        @Override
        public ChatClient.Builder defaultUser(Resource resource) {
            return this;
        }

        @Override
        public ChatClient.Builder defaultUser(Consumer<ChatClient.PromptUserSpec> userSpecConsumer) {
            return this;
        }

        @Override
        public ChatClient.Builder defaultSystem(String text) {
            return this;
        }

        @Override
        public ChatClient.Builder defaultSystem(Resource resource, Charset charset) {
            return this;
        }

        @Override
        public ChatClient.Builder defaultSystem(Resource resource) {
            return this;
        }

        @Override
        public ChatClient.Builder defaultSystem(Consumer<ChatClient.PromptSystemSpec> systemSpecConsumer) {
            return this;
        }

        @Override
        public ChatClient.Builder defaultTemplateRenderer(TemplateRenderer templateRenderer) {
            return this;
        }

        @Override
        public ChatClient.Builder defaultToolNames(String... toolNames) {
            return this;
        }

        @Override
        public ChatClient.Builder defaultTools(Object... toolObjects) {
            return this;
        }

        @Override
        public ChatClient.Builder defaultToolCallbacks(ToolCallback... toolCallbacks) {
            return this;
        }

        @Override
        public ChatClient.Builder defaultToolCallbacks(List<ToolCallback> toolCallbacks) {
            return this;
        }

        @Override
        public ChatClient.Builder defaultToolCallbacks(ToolCallbackProvider... toolCallbackProviders) {
            return this;
        }

        @Override
        public ChatClient.Builder defaultToolContext(Map<String, Object> toolContext) {
            return this;
        }

        @Override
        public ChatClient.Builder clone() {
            return this;
        }

        @Override
        public ChatClient build() {
            return new NoOpChatClient();
        }
    }

    private static final class NoOpChatClient implements ChatClient {

        @Override
        public ChatClient.ChatClientRequestSpec prompt() {
            throw new UnsupportedOperationException("prompt() should not be used in these parser tests");
        }

        @Override
        public ChatClient.ChatClientRequestSpec prompt(String text) {
            throw new UnsupportedOperationException("prompt(String) should not be used in these parser tests");
        }

        @Override
        public ChatClient.ChatClientRequestSpec prompt(org.springframework.ai.chat.prompt.Prompt prompt) {
            throw new UnsupportedOperationException("prompt(Prompt) should not be used in these parser tests");
        }

        @Override
        public ChatClient.Builder mutate() {
            return new NoOpChatClientBuilder();
        }
    }
}
