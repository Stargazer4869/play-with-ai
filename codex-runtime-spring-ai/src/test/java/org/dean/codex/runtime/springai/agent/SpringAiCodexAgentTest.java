package org.dean.codex.runtime.springai.agent;

import org.dean.codex.core.approval.CommandApprovalService;
import org.dean.codex.core.conversation.InMemoryConversationStore;
import org.dean.codex.core.tool.local.ShellCommandTool;
import org.dean.codex.protocol.approval.ApprovalId;
import org.dean.codex.protocol.approval.ApprovalStatus;
import org.dean.codex.protocol.approval.CommandApprovalRequest;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.conversation.TurnId;
import org.dean.codex.protocol.conversation.TurnStatus;
import org.dean.codex.protocol.tool.CommandApprovalDecision;
import org.dean.codex.protocol.tool.FileReadResult;
import org.dean.codex.protocol.tool.FilePatchResult;
import org.dean.codex.protocol.tool.FileSearchResult;
import org.dean.codex.protocol.tool.FileWriteResult;
import org.dean.codex.protocol.tool.SearchMatch;
import org.dean.codex.protocol.tool.ShellCommandResult;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringAiCodexAgentTest {

    private SpringAiCodexAgent agent;

    @BeforeEach
    void setUp() {
        agent = new SpringAiCodexAgent(
                new NoOpChatClientBuilder(),
                path -> new FileReadResult(true, path, "", false, 0, ""),
                (query, scope) -> new FileSearchResult(true, query, scope, List.of(new SearchMatch("pom.xml", 1, "<project>")), 1, false, ""),
                (path, oldText, newText, replaceAll) -> new FilePatchResult(true, path, 1, 0, ""),
                (path, content) -> new FileWriteResult(true, path, true, content == null ? 0 : content.length(), ""),
                new StubShellCommandTool(),
                new NoOpCommandApprovalService(),
                new InMemoryConversationStore(),
                Path.of("/tmp/workspace"),
                6,
                2,
                8
        );
    }

    @Test
    void parseDecisionSupportsMultipleActions() {
        SpringAiCodexAgent.PlannerStep step = agent.parseDecision("""
                {
                  "summary": "Inspect the project and then run tests",
                  "actions": [
                    {
                      "action": "SEARCH_FILES",
                      "query": "SpringBootApplication"
                    },
                    {
                      "action": "APPLY_PATCH",
                      "path": "README.md",
                      "oldText": "foo",
                      "newText": "bar"
                    }
                  ]
                }
                """);

        assertFalse(step.isFinished());
        assertNull(step.validationError());
        assertEquals(2, step.actions().size());
        assertEquals(SpringAiCodexAgent.ToolAction.SEARCH_FILES, step.actions().get(0).action());
        assertEquals("SpringBootApplication", step.actions().get(0).query());
        assertEquals(SpringAiCodexAgent.ToolAction.APPLY_PATCH, step.actions().get(1).action());
        assertEquals("README.md", step.actions().get(1).path());
        assertEquals("foo", step.actions().get(1).oldText());
        assertEquals("bar", step.actions().get(1).newText());
    }

    @Test
    void parseDecisionRejectsMoreThanConfiguredMaxActions() {
        SpringAiCodexAgent.PlannerStep step = agent.parseDecision("""
                {
                  "summary": "Try too many actions",
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
        SpringAiCodexAgent.PlannerStep step = agent.parseDecision("""
                {
                  "thought": "Read one file",
                  "action": "READ_FILE",
                  "path": "README.md"
                }
                """);

        assertFalse(step.isFinished());
        assertNull(step.validationError());
        assertEquals(List.of(SpringAiCodexAgent.ToolAction.READ_FILE),
                step.actions().stream().map(SpringAiCodexAgent.ToolActionRequest::action).toList());
        assertEquals("README.md", step.actions().get(0).path());
    }

    @Test
    void parseDecisionRejectsPatchWithoutOldText() {
        SpringAiCodexAgent.PlannerStep step = agent.parseDecision("""
                {
                  "summary": "Patch a file",
                  "actions": [
                    {"action": "APPLY_PATCH", "path": "README.md", "newText": "bar"}
                  ]
                }
                """);

        assertFalse(step.isFinished());
        assertTrue(step.validationError().contains("requires oldText"));
    }

    @Test
    void handleTurnReturnsStructuredResult() {
        ThreadId threadId = new ThreadId("thread-1");
        TurnId turnId = new TurnId("turn-1");

        SpringAiCodexAgent.PlannerStep step = agent.parseDecision("""
                {
                  "summary": "Task is done",
                  "finalAnswer": "All set"
                }
                """);

        assertTrue(step.isFinished());
        var result = agent.handleTurn(threadId, turnId, "done?");
        assertEquals(threadId, result.threadId());
        assertEquals(turnId, result.turnId());
        assertEquals(TurnStatus.FAILED, result.status());
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

    private static final class NoOpCommandApprovalService implements CommandApprovalService {

        @Override
        public CommandApprovalRequest requestApproval(ThreadId threadId, TurnId turnId, String command, String workingDirectory, String reason) {
            return new CommandApprovalRequest(
                    new ApprovalId("approval-1"),
                    threadId,
                    turnId,
                    command,
                    workingDirectory,
                    reason,
                    ApprovalStatus.PENDING,
                    Instant.now(),
                    Instant.now(),
                    "",
                    null);
        }

        @Override
        public List<CommandApprovalRequest> approvals(ThreadId threadId) {
            return List.of();
        }

        @Override
        public CommandApprovalRequest approve(ThreadId threadId, String approvalIdPrefix) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CommandApprovalRequest reject(ThreadId threadId, String approvalIdPrefix, String reason) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StubShellCommandTool implements ShellCommandTool {

        @Override
        public ShellCommandResult runCommand(String command) {
            return new ShellCommandResult(true, command, 0, "", "", false, "/tmp/workspace", true,
                    CommandApprovalDecision.ALLOW, "Allowed", "");
        }

        @Override
        public ShellCommandResult runApprovedCommand(String command) {
            return new ShellCommandResult(true, command, 0, "", "", false, "/tmp/workspace", true,
                    CommandApprovalDecision.ALLOW, "Explicitly approved", "");
        }
    }
}
