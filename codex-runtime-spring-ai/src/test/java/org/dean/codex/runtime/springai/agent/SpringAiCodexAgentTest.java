package org.dean.codex.runtime.springai.agent;

import org.dean.codex.core.approval.CommandApprovalService;
import org.dean.codex.core.agent.AgentControl;
import org.dean.codex.core.context.ContextManager;
import org.dean.codex.core.context.ThreadContextReconstructionService;
import org.dean.codex.core.conversation.InMemoryConversationStore;
import org.dean.codex.core.skill.ResolvedSkill;
import org.dean.codex.core.skill.SkillService;
import org.dean.codex.core.tool.local.ShellCommandTool;
import org.dean.codex.protocol.approval.ApprovalId;
import org.dean.codex.protocol.approval.ApprovalStatus;
import org.dean.codex.protocol.approval.CommandApprovalRequest;
import org.dean.codex.protocol.agent.AgentMessage;
import org.dean.codex.protocol.agent.AgentSpawnRequest;
import org.dean.codex.protocol.agent.AgentStatus;
import org.dean.codex.protocol.agent.AgentSummary;
import org.dean.codex.protocol.agent.AgentWaitResult;
import org.dean.codex.protocol.context.ReconstructedTurnActivity;
import org.dean.codex.protocol.context.ReconstructedThreadContext;
import org.dean.codex.protocol.conversation.ConversationMessage;
import org.dean.codex.protocol.conversation.MessageRole;
import org.dean.codex.protocol.context.ThreadMemory;
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
import org.dean.codex.protocol.item.ToolCallItem;
import org.dean.codex.protocol.item.ToolResultItem;
import org.dean.codex.runtime.springai.config.CodexProperties;
import org.dean.codex.protocol.skill.SkillMetadata;
import org.dean.codex.protocol.skill.SkillScope;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
                new NoOpAgentControl(),
                new NoOpThreadContextReconstructionService(),
                new NoOpContextManager(),
                new NoOpSkillService(),
                Path.of("/tmp/workspace"),
                defaultModelProperties()
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
        assertTrue(step.validationError().contains("configured per-step limit of 2"));
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
    void parseDecisionSupportsEditPlanWithCommandAction() {
        SpringAiCodexAgent.PlannerStep step = agent.parseDecision("""
                {
                  "summary": "Verify the README adjustment",
                  "editPlan": {
                    "summary": "Validate the README adjustment",
                    "edits": [
                      {
                        "path": "README.md",
                        "type": "MODIFY",
                        "description": "document the new CLI streaming behavior"
                      }
                    ]
                  },
                  "actions": [
                    {"action": "RUN_COMMAND", "command": "git diff -- README.md"}
                  ]
                }
                """);

        assertFalse(step.isFinished());
        assertNull(step.validationError());
        assertEquals("Validate the README adjustment", step.editPlan().summary());
        assertEquals(1, step.editPlan().edits().size());
        assertEquals(SpringAiCodexAgent.ToolAction.RUN_COMMAND, step.actions().get(0).action());
        assertEquals("git diff -- README.md", step.actions().get(0).command());
    }

    @Test
    void parseDecisionRejectsCommandWithoutCommandText() {
        SpringAiCodexAgent.PlannerStep step = agent.parseDecision("""
                {
                  "summary": "Run a command",
                  "actions": [
                    {"action": "RUN_COMMAND"}
                  ]
                }
                """);

        assertFalse(step.isFinished());
        assertTrue(step.validationError().contains("command"));
    }

    @Test
    void parseDecisionSupportsSubAgentActions() {
        SpringAiCodexAgent parsingAgent = new SpringAiCodexAgent(
                new NoOpChatClientBuilder(),
                path -> new FileReadResult(true, path, "", false, 0, ""),
                (query, scope) -> new FileSearchResult(true, query, scope, List.of(), 0, false, ""),
                (path, oldText, newText, replaceAll) -> new FilePatchResult(true, path, 1, 0, ""),
                (path, content) -> new FileWriteResult(true, path, true, content == null ? 0 : content.length(), ""),
                new StubShellCommandTool(),
                new NoOpCommandApprovalService(),
                new NoOpAgentControl(),
                new NoOpThreadContextReconstructionService(),
                new NoOpContextManager(),
                new NoOpSkillService(),
                Path.of("/tmp/workspace"),
                codexProperties(6, 8, 0, 0));

        SpringAiCodexAgent.PlannerStep step = parsingAgent.parseDecision("""
                {
                  "summary": "Delegate work to sub-agents",
                  "actions": [
                    {
                      "action": "spawn_agent",
                      "taskName": "inspect repository",
                      "prompt": "inspect repository",
                      "nickname": "inspector",
                      "role": "reviewer",
                      "depth": 1,
                      "modelProvider": "openai",
                      "model": "gpt-5",
                      "cwd": "subdir"
                    },
                    {
                      "action": "send_input",
                      "threadId": "agent-1",
                      "content": "continue",
                      "interrupt": true
                    },
                    {
                      "action": "wait_agent",
                      "threadIds": ["agent-1", "agent-2"],
                      "timeoutMillis": 2500
                    },
                    {
                      "action": "resume_agent",
                      "threadId": "agent-1"
                    },
                    {
                      "action": "close_agent",
                      "threadId": "agent-1"
                    },
                    {
                      "action": "list_agents",
                      "threadId": "thread-parent",
                      "recursive": false
                    }
                  ]
                }
                """);

        assertFalse(step.isFinished());
        assertNull(step.validationError());
        assertEquals(6, step.actions().size());
        assertEquals(SpringAiCodexAgent.ToolAction.SPAWN_AGENT, step.actions().get(0).action());
        assertEquals("inspect repository", step.actions().get(0).taskName());
        assertEquals(SpringAiCodexAgent.ToolAction.SEND_INPUT, step.actions().get(1).action());
        assertEquals("agent-1", step.actions().get(1).threadId());
        assertEquals(List.of(new ThreadId("agent-1"), new ThreadId("agent-2")), step.actions().get(2).threadIds());
        assertEquals(Long.valueOf(2500), step.actions().get(2).timeoutMillis());
        assertEquals(SpringAiCodexAgent.ToolAction.LIST_AGENTS, step.actions().get(5).action());
        assertFalse(step.actions().get(5).recursive());
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

    @Test
    void userPromptIncludesSelectedSkillInstructions() {
        InMemoryConversationStore conversationStore = new InMemoryConversationStore();
        ThreadId threadId = conversationStore.createThread("Skill prompt thread");
        SkillMetadata metadata = new SkillMetadata(
                "reviewer",
                "Review code for bugs.",
                "Review code for bugs.",
                "/tmp/reviewer/SKILL.md",
                SkillScope.USER,
                true);
        agent = new SpringAiCodexAgent(
                new NoOpChatClientBuilder(),
                path -> new FileReadResult(true, path, "", false, 0, ""),
                (query, scope) -> new FileSearchResult(true, query, scope, List.of(), 0, false, ""),
                (path, oldText, newText, replaceAll) -> new FilePatchResult(true, path, 1, 0, ""),
                (path, content) -> new FileWriteResult(true, path, true, content == null ? 0 : content.length(), ""),
                new StubShellCommandTool(),
                new NoOpCommandApprovalService(),
                new NoOpAgentControl(),
                new FixedThreadContextReconstructionService(new ReconstructedThreadContext(
                        threadId,
                        new ThreadMemory(
                                "memory-1",
                                threadId,
                                "Compacted earlier thread context.",
                                List.of(),
                                0,
                                Instant.now()),
                        List.of(new ConversationMessage(new TurnId("turn-1"), MessageRole.USER, "Replay canonical history", Instant.now())),
                        List.of(),
                        List.of(new ReconstructedTurnActivity(new TurnId("turn-1"), "historyToolCall", "toolCall: READ_FILE README.md", Instant.now())),
                        Instant.now())),
                new NoOpContextManager(),
                new StaticSkillService(new ResolvedSkill(metadata, "# reviewer\n\nReview carefully.")),
                Path.of("/tmp/workspace"),
                defaultModelProperties()
        );

        var selectedSkills = agent.selectedSkillsForInput("Please use $reviewer");
        String prompt = agent.buildUserPrompt(
                new ReconstructedThreadContext(
                        threadId,
                        new ThreadMemory("memory-1", threadId, "Compacted earlier thread context.", List.of(), 0, Instant.now()),
                        List.of(new ConversationMessage(new TurnId("turn-1"), MessageRole.USER, "Replay canonical history", Instant.now())),
                        List.of(),
                        List.of(new ReconstructedTurnActivity(new TurnId("turn-1"), "historyToolCall", "toolCall: READ_FILE README.md", Instant.now())),
                        Instant.now()),
                "Please use $reviewer",
                "",
                1,
                selectedSkills,
                List.of());

        assertTrue(prompt.contains("Skill: reviewer"));
        assertTrue(prompt.contains("Review carefully."));
        assertTrue(prompt.contains("USER: Replay canonical history"));
        assertTrue(prompt.contains("toolCall: READ_FILE README.md"));
        assertFalse(prompt.contains("Compacted thread memory"));
        assertFalse(prompt.contains("Compacted earlier thread context."));
    }

    @Test
    void autoCompactionDoesNotTriggerWhenPromptEstimateIsBelowLimit() {
        RecordingContextManager contextManager = new RecordingContextManager();
        SpringAiCodexAgent agent = new ScriptedSpringAiCodexAgent(
                new NoOpChatClientBuilder(),
                path -> new FileReadResult(true, path, "", false, 0, ""),
                (query, scope) -> new FileSearchResult(true, query, scope, List.of(), 0, false, ""),
                (path, oldText, newText, replaceAll) -> new FilePatchResult(true, path, 1, 0, ""),
                (path, content) -> new FileWriteResult(true, path, true, content == null ? 0 : content.length(), ""),
                new StubShellCommandTool(),
                new NoOpCommandApprovalService(),
                new NoOpAgentControl(),
                new FixedThreadContextReconstructionService(smallPromptContext()),
                contextManager,
                new NoOpSkillService(),
                Path.of("/tmp/workspace"),
                codexProperties(6, 2, 2200, 0),
                List.of("""
                        {
                          "summary": "All done",
                          "finalAnswer": "Finished"
                        }
                        """),
                List.of(0));

        var result = agent.handleTurn(new ThreadId("thread-1"), new TurnId("turn-1"), "small prompt");

        assertEquals(0, contextManager.compactionCount.get());
        assertEquals(TurnStatus.COMPLETED, result.status());
    }

    @Test
    void autoCompactionTriggersBeforeSamplingWhenPromptEstimateIsOverLimit() {
        RecordingContextManager contextManager = new RecordingContextManager();
        SpringAiCodexAgent agent = new ScriptedSpringAiCodexAgent(
                new NoOpChatClientBuilder(),
                path -> new FileReadResult(true, path, "", false, 0, ""),
                (query, scope) -> new FileSearchResult(true, query, scope, List.of(), 0, false, ""),
                (path, oldText, newText, replaceAll) -> new FilePatchResult(true, path, 1, 0, ""),
                (path, content) -> new FileWriteResult(true, path, true, content == null ? 0 : content.length(), ""),
                new StubShellCommandTool(),
                new NoOpCommandApprovalService(),
                new NoOpAgentControl(),
                new FixedThreadContextReconstructionService(largePromptContext()),
                contextManager,
                new NoOpSkillService(),
                Path.of("/tmp/workspace"),
                codexProperties(6, 2, 2200, 0),
                List.of("""
                        {
                          "summary": "Done",
                          "finalAnswer": "Finished"
                        }
                        """),
                List.of(1));

        var result = agent.handleTurn(new ThreadId("thread-1"), new TurnId("turn-1"), "large prompt");

        assertEquals(1, contextManager.compactionCount.get());
        assertEquals(TurnStatus.COMPLETED, result.status());
    }

    @Test
    void autoCompactionTriggersAfterActionsWhenNextPromptWouldExceedLimit() {
        RecordingContextManager contextManager = new RecordingContextManager();
        PlanningState planningState = new PlanningState();
        SpringAiCodexAgent agent = new ScriptedSpringAiCodexAgent(
                new NoOpChatClientBuilder(),
                path -> new FileReadResult(true, path, "", false, 0, ""),
                (query, scope) -> new FileSearchResult(true, query, scope, List.of(), 0, false, ""),
                (path, oldText, newText, replaceAll) -> new FilePatchResult(true, path, 1, 0, ""),
                (path, content) -> new FileWriteResult(true, path, true, content == null ? 0 : content.length(), ""),
                new StubShellCommandTool(planningState),
                new NoOpCommandApprovalService(),
                new NoOpAgentControl(),
                new MutableThreadContextReconstructionService(planningState, contextManager),
                contextManager,
                new NoOpSkillService(),
                Path.of("/tmp/workspace"),
                codexProperties(6, 2, 2200, 0),
                List.of("""
                        {
                          "summary": "Run the command",
                          "actions": [
                            {"action": "RUN_COMMAND", "command": "echo big-output"}
                          ]
                        }
                        """,
                        """
                        {
                          "summary": "Done",
                          "finalAnswer": "Finished"
                        }
                        """),
                List.of(0, 1));

        var result = agent.handleTurn(new ThreadId("thread-1"), new TurnId("turn-1"), "run command");

        assertEquals(1, contextManager.compactionCount.get());
        assertEquals(TurnStatus.COMPLETED, result.status());
    }

    @Test
    void autoCompactionCanTriggerBeforeSamplingAndAfterActionsInTheSameTurn() {
        RecordingContextManager contextManager = new RecordingContextManager();
        PlanningState planningState = new PlanningState();
        ThreadContextReconstructionService reconstructionService = threadId -> {
            if (planningState.commandExecuted.get() && contextManager.compactionCount.get() == 1) {
                return largePromptContext();
            }
            if (contextManager.compactionCount.get() >= 2) {
                return smallPromptContext();
            }
            return largePromptContext();
        };
        SpringAiCodexAgent agent = new ScriptedSpringAiCodexAgent(
                new NoOpChatClientBuilder(),
                path -> new FileReadResult(true, path, "", false, 0, ""),
                (query, scope) -> new FileSearchResult(true, query, scope, List.of(), 0, false, ""),
                (path, oldText, newText, replaceAll) -> new FilePatchResult(true, path, 1, 0, ""),
                (path, content) -> new FileWriteResult(true, path, true, content == null ? 0 : content.length(), ""),
                new StubShellCommandTool(planningState),
                new NoOpCommandApprovalService(),
                new NoOpAgentControl(),
                reconstructionService,
                contextManager,
                new NoOpSkillService(),
                Path.of("/tmp/workspace"),
                codexProperties(6, 2, 1000, 0),
                List.of("""
                        {
                          "summary": "Run the command",
                          "actions": [
                            {"action": "RUN_COMMAND", "command": "echo large-output"}
                          ]
                        }
                        """,
                        """
                        {
                          "summary": "Done",
                          "finalAnswer": "Finished"
                        }
                        """),
                List.of(1, 2));

        var result = agent.handleTurn(new ThreadId("thread-1"), new TurnId("turn-1"), "run command");

        assertEquals(2, contextManager.compactionCount.get());
        assertEquals(TurnStatus.COMPLETED, result.status());
    }

    @Test
    void handleTurnRoutesSubAgentActionsThroughAgentControl() {
        RecordingAgentControl agentControl = new RecordingAgentControl();
        SpringAiCodexAgent agent = new ScriptedSpringAiCodexAgent(
                new NoOpChatClientBuilder(),
                path -> new FileReadResult(true, path, "", false, 0, ""),
                (query, scope) -> new FileSearchResult(true, query, scope, List.of(), 0, false, ""),
                (path, oldText, newText, replaceAll) -> new FilePatchResult(true, path, 1, 0, ""),
                (path, content) -> new FileWriteResult(true, path, true, content == null ? 0 : content.length(), ""),
                new StubShellCommandTool(),
                new NoOpCommandApprovalService(),
                agentControl,
                new NoOpThreadContextReconstructionService(),
                new RecordingContextManager(),
                new NoOpSkillService(),
                Path.of("/tmp/workspace"),
                codexProperties(8, 2, 0, 0),
                List.of("""
                        {
                          "summary": "Spawn a helper",
                          "actions": [
                            {
                              "action": "spawn_agent",
                              "taskName": "inspect repository",
                              "prompt": "inspect repository",
                              "nickname": "inspector",
                              "role": "reviewer",
                              "depth": 1
                            }
                          ]
                        }
                        """,
                        """
                        {
                          "summary": "Message the helper",
                          "actions": [
                            {
                              "action": "send_input",
                              "threadId": "agent-1",
                              "content": "continue",
                              "interrupt": true
                            }
                          ]
                        }
                        """,
                        """
                        {
                          "summary": "Wait for the helper",
                          "actions": [
                            {
                              "action": "wait_agent",
                              "threadIds": ["agent-1"],
                              "timeoutMillis": 1000
                            }
                          ]
                        }
                        """,
                        """
                        {
                          "summary": "Resume the helper",
                          "actions": [
                            {
                              "action": "resume_agent",
                              "threadId": "agent-1"
                            }
                          ]
                        }
                        """,
                        """
                        {
                          "summary": "Close the helper",
                          "actions": [
                            {
                              "action": "close_agent",
                              "threadId": "agent-1"
                            }
                          ]
                        }
                        """,
                        """
                        {
                          "summary": "List helper agents",
                          "actions": [
                            {
                              "action": "list_agents",
                              "recursive": true
                            }
                          ]
                        }
                        """,
                        """
                        {
                          "summary": "Done",
                          "finalAnswer": "Finished"
                        }
                        """),
                List.of(0, 0, 0, 0, 0, 0, 0));

        ThreadId threadId = new ThreadId("thread-parent");
        var result = agent.handleTurn(threadId, new TurnId("turn-1"), "delegate subagents");

        assertEquals(TurnStatus.COMPLETED, result.status());
        assertEquals("inspect repository", agentControl.spawnRequest.taskName());
        assertEquals(threadId, agentControl.spawnRequest.parentThreadId());
        assertEquals(new ThreadId("agent-1"), agentControl.sendInputThreadId);
        assertEquals("continue", agentControl.sendInputMessage.content());
        assertEquals(List.of(new ThreadId("agent-1")), agentControl.waitThreadIds);
        assertEquals(new ThreadId("agent-1"), agentControl.resumeThreadId);
        assertEquals(new ThreadId("agent-1"), agentControl.closeThreadId);
        assertEquals(threadId, agentControl.listAgentsParentThreadId);
        assertTrue(result.items().stream().anyMatch(ToolCallItem.class::isInstance));
        assertTrue(result.items().stream().anyMatch(ToolResultItem.class::isInstance));
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

        private final PlanningState planningState;

        private StubShellCommandTool() {
            this(new PlanningState());
        }

        private StubShellCommandTool(PlanningState planningState) {
            this.planningState = planningState;
        }

        @Override
        public ShellCommandResult runCommand(String command) {
            planningState.commandExecuted.set(true);
            return new ShellCommandResult(true, command, 0, "", "", false, "/tmp/workspace", true,
                    CommandApprovalDecision.ALLOW, "Allowed", bigOutput());
        }

        @Override
        public ShellCommandResult runApprovedCommand(String command) {
            planningState.commandExecuted.set(true);
            return new ShellCommandResult(true, command, 0, "", "", false, "/tmp/workspace", true,
                    CommandApprovalDecision.ALLOW, "Explicitly approved", bigOutput());
        }

        private String bigOutput() {
            return "x".repeat(5000);
        }
    }

    private static final class NoOpSkillService implements SkillService {

        @Override
        public List<SkillMetadata> listSkills(boolean forceReload) {
            return List.of();
        }

        @Override
        public List<ResolvedSkill> resolveSkills(String input, boolean forceReload) {
            return List.of();
        }
    }

    private static final class NoOpAgentControl implements AgentControl {

        @Override
        public AgentSummary spawnAgent(AgentSpawnRequest request) {
            return summary(request == null ? null : request.parentThreadId(), new ThreadId("agent-noop"), AgentStatus.IDLE);
        }

        @Override
        public AgentSummary sendInput(ThreadId agentThreadId, AgentMessage message, boolean interrupt) {
            return summary(null, agentThreadId, AgentStatus.RUNNING);
        }

        @Override
        public AgentWaitResult waitAgent(List<ThreadId> agentThreadIds, long timeoutMillis) {
            ThreadId threadId = agentThreadIds == null || agentThreadIds.isEmpty() ? null : agentThreadIds.get(0);
            return new AgentWaitResult(threadId, null, AgentStatus.RUNNING, AgentStatus.IDLE, true, "Timed out.", "", Instant.now());
        }

        @Override
        public AgentSummary resumeAgent(ThreadId agentThreadId) {
            return summary(null, agentThreadId, AgentStatus.IDLE);
        }

        @Override
        public AgentSummary closeAgent(ThreadId agentThreadId) {
            return summary(null, agentThreadId, AgentStatus.SHUTDOWN);
        }

        @Override
        public List<AgentSummary> listAgents(ThreadId parentThreadId, boolean recursive) {
            return List.of();
        }

        private AgentSummary summary(ThreadId parentThreadId, ThreadId threadId, AgentStatus status) {
            Instant now = Instant.now();
            return new AgentSummary(threadId, parentThreadId, "noop", "noop", "noop", 1, status, now, now, status == AgentStatus.SHUTDOWN ? now : null);
        }
    }

    private static final class RecordingAgentControl implements AgentControl {

        private AgentSpawnRequest spawnRequest;
        private AgentMessage sendInputMessage;
        private ThreadId sendInputThreadId;
        private List<ThreadId> waitThreadIds = List.of();
        private long waitTimeoutMillis;
        private ThreadId resumeThreadId;
        private ThreadId closeThreadId;
        private ThreadId listAgentsParentThreadId;
        private boolean listAgentsRecursive;

        @Override
        public AgentSummary spawnAgent(AgentSpawnRequest request) {
            this.spawnRequest = request;
            Instant now = Instant.parse("2026-04-01T00:00:00Z");
            return new AgentSummary(new ThreadId("agent-1"), request.parentThreadId(), "inspector", "reviewer", "subdir", 1, AgentStatus.IDLE, now, now, null);
        }

        @Override
        public AgentSummary sendInput(ThreadId agentThreadId, AgentMessage message, boolean interrupt) {
            this.sendInputThreadId = agentThreadId;
            this.sendInputMessage = message;
            Instant now = Instant.parse("2026-04-01T00:01:00Z");
            return new AgentSummary(agentThreadId, message == null ? null : message.senderThreadId(), "inspector", "reviewer", "subdir", 1, AgentStatus.RUNNING, now, now, null);
        }

        @Override
        public AgentWaitResult waitAgent(List<ThreadId> agentThreadIds, long timeoutMillis) {
            this.waitThreadIds = agentThreadIds == null ? List.of() : List.copyOf(agentThreadIds);
            this.waitTimeoutMillis = timeoutMillis;
            ThreadId threadId = this.waitThreadIds.isEmpty() ? null : this.waitThreadIds.get(0);
            return new AgentWaitResult(threadId, new TurnId("turn-1"), AgentStatus.RUNNING, AgentStatus.WAITING, false, "Agent mailbox changed.", "turn completed", Instant.parse("2026-04-01T00:02:00Z"));
        }

        @Override
        public AgentSummary resumeAgent(ThreadId agentThreadId) {
            this.resumeThreadId = agentThreadId;
            Instant now = Instant.parse("2026-04-01T00:03:00Z");
            return new AgentSummary(agentThreadId, new ThreadId("thread-parent"), "inspector", "reviewer", "subdir", 1, AgentStatus.IDLE, now, now, null);
        }

        @Override
        public AgentSummary closeAgent(ThreadId agentThreadId) {
            this.closeThreadId = agentThreadId;
            Instant now = Instant.parse("2026-04-01T00:04:00Z");
            return new AgentSummary(agentThreadId, new ThreadId("thread-parent"), "inspector", "reviewer", "subdir", 1, AgentStatus.SHUTDOWN, now, now, now);
        }

        @Override
        public List<AgentSummary> listAgents(ThreadId parentThreadId, boolean recursive) {
            this.listAgentsParentThreadId = parentThreadId;
            this.listAgentsRecursive = recursive;
            return List.of(
                    new AgentSummary(new ThreadId("agent-1"), parentThreadId, "inspector", "reviewer", "subdir", 1, AgentStatus.IDLE, Instant.parse("2026-04-01T00:00:00Z"), Instant.parse("2026-04-01T00:01:00Z"), null));
        }
    }

    private static final class NoOpThreadContextReconstructionService implements ThreadContextReconstructionService {

        @Override
        public ReconstructedThreadContext reconstruct(ThreadId threadId) {
            return new ReconstructedThreadContext(threadId, null, List.of(), List.of(), List.of(), Instant.now());
        }
    }

    private static final class NoOpContextManager implements ContextManager {

        @Override
        public java.util.Optional<ThreadMemory> latestThreadMemory(ThreadId threadId) {
            return java.util.Optional.empty();
        }

        @Override
        public ThreadMemory compactThread(ThreadId threadId) {
            return new ThreadMemory("memory-0", threadId, "summary", List.of(), 0, Instant.now());
        }
    }

    private static final class MutableThreadContextReconstructionService implements ThreadContextReconstructionService {

        private final PlanningState planningState;
        private final RecordingContextManager contextManager;

        private MutableThreadContextReconstructionService(PlanningState planningState, RecordingContextManager contextManager) {
            this.planningState = planningState;
            this.contextManager = contextManager;
        }

        @Override
        public ReconstructedThreadContext reconstruct(ThreadId threadId) {
            return planningState.commandExecuted.get() && contextManager.compactionCount.get() == 0
                    ? largePromptContext()
                    : smallPromptContext();
        }
    }

    private static final class FixedThreadContextReconstructionService implements ThreadContextReconstructionService {

        private final ReconstructedThreadContext reconstructedThreadContext;

        private FixedThreadContextReconstructionService(ReconstructedThreadContext reconstructedThreadContext) {
            this.reconstructedThreadContext = reconstructedThreadContext;
        }

        @Override
        public ReconstructedThreadContext reconstruct(ThreadId threadId) {
            return reconstructedThreadContext;
        }
    }

    private static final class ScriptedSpringAiCodexAgent extends SpringAiCodexAgent {

        private final Deque<String> responses;
        private final List<Integer> expectedCompactionsAtDecision;
        private final RecordingContextManager contextManager;
        private final AtomicInteger decisionCount = new AtomicInteger();

        private ScriptedSpringAiCodexAgent(ChatClient.Builder chatClientBuilder,
                                           org.dean.codex.core.tool.local.FileReaderTool fileReaderTool,
                                           org.dean.codex.core.tool.local.FileSearchTool fileSearchTool,
                                           org.dean.codex.core.tool.local.FilePatchTool filePatchTool,
                                           org.dean.codex.core.tool.local.FileWriterTool fileWriterTool,
                                           ShellCommandTool shellCommandTool,
                                           CommandApprovalService commandApprovalService,
                                           AgentControl agentControl,
                                           ThreadContextReconstructionService threadContextReconstructionService,
                                           RecordingContextManager contextManager,
                                           SkillService skillService,
                                           Path workspaceRoot,
                                           CodexProperties codexProperties,
                                           List<String> responses,
                                           List<Integer> expectedCompactionsAtDecision) {
            super(chatClientBuilder,
                    fileReaderTool,
                    fileSearchTool,
                    filePatchTool,
                    fileWriterTool,
                    shellCommandTool,
                    commandApprovalService,
                    agentControl,
                    threadContextReconstructionService,
                    contextManager,
                    skillService,
                    workspaceRoot,
                    codexProperties);
            this.responses = new ArrayDeque<>(responses);
            this.expectedCompactionsAtDecision = List.copyOf(expectedCompactionsAtDecision);
            this.contextManager = contextManager;
        }

        @Override
        protected PlannerStep requestDecision(ThreadId threadId,
                                              String input,
                                              String scratchpad,
                                              int step,
                                              List<ResolvedSkill> selectedSkills,
                                              List<SkillMetadata> availableSkills,
                                              List<String> steeringInputs) {
            int index = decisionCount.getAndIncrement();
            int expected = expectedCompactionsAtDecision.get(Math.min(index, expectedCompactionsAtDecision.size() - 1));
            assertEquals(expected, contextManager.compactionCount.get());
            String response = responses.isEmpty() ? """
                    {
                      "summary": "Done",
                      "finalAnswer": "Finished"
                    }
                    """ : responses.removeFirst();
            return parseDecision(response);
        }
    }

    private static final class RecordingContextManager implements ContextManager {

        private final AtomicInteger compactionCount = new AtomicInteger();

        @Override
        public java.util.Optional<ThreadMemory> latestThreadMemory(ThreadId threadId) {
            return java.util.Optional.of(new ThreadMemory("memory-1", threadId, "summary", List.of(), 0, Instant.now()));
        }

        @Override
        public ThreadMemory compactThread(ThreadId threadId) {
            compactionCount.incrementAndGet();
            return new ThreadMemory("memory-1", threadId, "summary", List.of(), 0, Instant.now());
        }
    }

    private static final class PlanningState {

        private final AtomicBoolean commandExecuted = new AtomicBoolean();
    }

    private static CodexProperties defaultModelProperties() {
        return codexProperties(6, 2, 0, 0);
    }

    private static CodexProperties codexProperties(int maxSteps,
                                                   int maxActionsPerStep,
                                                   int autoCompactTokenLimit,
                                                   int contextWindow) {
        CodexProperties properties = new CodexProperties();
        properties.getAgent().setMaxSteps(maxSteps);
        properties.getAgent().setMaxActionsPerStep(maxActionsPerStep);
        properties.getModel().setAutoCompactTokenLimit(autoCompactTokenLimit);
        properties.getModel().setContextWindow(contextWindow);
        return properties;
    }

    private static ReconstructedThreadContext smallPromptContext() {
        return new ReconstructedThreadContext(
                new ThreadId("thread-1"),
                new ThreadMemory("memory-1", new ThreadId("thread-1"), "summary", List.of(), 0, Instant.now()),
                List.of(new ConversationMessage(new TurnId("turn-1"), MessageRole.USER, "small prompt", Instant.now())),
                List.of(),
                List.of(),
                Instant.now());
    }

    private static ReconstructedThreadContext largePromptContext() {
        String largeText = "large prompt ".repeat(600);
        return new ReconstructedThreadContext(
                new ThreadId("thread-1"),
                new ThreadMemory("memory-1", new ThreadId("thread-1"), "summary", List.of(), 0, Instant.now()),
                List.of(new ConversationMessage(new TurnId("turn-1"), MessageRole.USER, largeText, Instant.now())),
                List.of(),
                List.of(),
                Instant.now());
    }

    private static final class StaticSkillService implements SkillService {

        private final ResolvedSkill resolvedSkill;

        private StaticSkillService(ResolvedSkill resolvedSkill) {
            this.resolvedSkill = resolvedSkill;
        }

        @Override
        public List<SkillMetadata> listSkills(boolean forceReload) {
            return List.of(resolvedSkill.metadata());
        }

        @Override
        public List<ResolvedSkill> resolveSkills(String input, boolean forceReload) {
            return input != null && input.contains("$" + resolvedSkill.metadata().name())
                    ? List.of(resolvedSkill)
                    : List.of();
        }
    }
}
