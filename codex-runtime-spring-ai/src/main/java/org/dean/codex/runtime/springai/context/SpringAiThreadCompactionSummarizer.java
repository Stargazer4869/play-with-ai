package org.dean.codex.runtime.springai.context;

import org.dean.codex.protocol.conversation.MessageRole;
import org.dean.codex.protocol.conversation.ThreadId;
import org.dean.codex.protocol.history.HistoryApprovalItem;
import org.dean.codex.protocol.history.HistoryCompactionSummaryItem;
import org.dean.codex.protocol.history.HistoryMessageItem;
import org.dean.codex.protocol.history.HistoryPlanItem;
import org.dean.codex.protocol.history.HistoryRuntimeErrorItem;
import org.dean.codex.protocol.history.HistorySkillUseItem;
import org.dean.codex.protocol.history.HistoryToolCallItem;
import org.dean.codex.protocol.history.HistoryToolResultItem;
import org.dean.codex.protocol.history.ThreadHistoryItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class SpringAiThreadCompactionSummarizer implements ThreadCompactionSummarizer {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiThreadCompactionSummarizer.class);

    private final ChatClient chatClient;

    public SpringAiThreadCompactionSummarizer(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.clone().build();
    }

    @Override
    public String summarize(ThreadId threadId, List<ThreadHistoryItem> compactedHistory, List<ThreadHistoryItem> retainedHistory) {
        String systemPrompt = """
                You compact older Codex thread history into a concise handoff summary.
                Return plain text only.
                Keep the summary short but complete enough to continue the work.
                Include the goal, the important decisions, the current state, and unresolved follow-ups.
                Do not restate the retained recent history verbatim.
                """;
        String userPrompt = """
                Thread id: %s

                Older history to summarize:
                %s

                Retained recent history that will stay visible:
                %s
                """.formatted(
                threadId == null ? "<null>" : threadId.value(),
                renderHistory(compactedHistory),
                renderHistory(retainedHistory));
        logger.debug("Compaction prompt for thread {}\n{}", threadId == null ? "<null>" : threadId.value(), userPrompt);
        String response = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
        return response == null ? "" : response.trim();
    }

    private String renderHistory(List<ThreadHistoryItem> historyItems) {
        if (historyItems == null || historyItems.isEmpty()) {
            return "(none)";
        }
        return historyItems.stream()
                .map(this::renderItem)
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private String renderItem(ThreadHistoryItem item) {
        if (item instanceof HistoryCompactionSummaryItem summaryItem) {
            return "COMPACTION SUMMARY[" + summaryItem.anchorTurnId().value() + "]: " + summaryItem.summaryText();
        }
        if (item instanceof HistoryMessageItem messageItem) {
            return messageItem.role().name().toLowerCase(Locale.ROOT) + ": " + messageItem.text();
        }
        if (item instanceof HistoryPlanItem planItem) {
            return "plan: " + (planItem.plan() == null ? "(none)" : planItem.plan().summary());
        }
        if (item instanceof HistorySkillUseItem skillUseItem) {
            return "skills: " + skillUseItem.skills().stream().map(skill -> skill.name()).collect(Collectors.joining(", "));
        }
        if (item instanceof HistoryToolCallItem toolCallItem) {
            return "toolCall: " + toolCallItem.toolName() + " " + toolCallItem.target();
        }
        if (item instanceof HistoryToolResultItem toolResultItem) {
            return "toolResult: " + toolResultItem.toolName() + " " + toolResultItem.summary();
        }
        if (item instanceof HistoryApprovalItem approvalItem) {
            return "approval: " + approvalItem.state() + " " + approvalItem.detail();
        }
        if (item instanceof HistoryRuntimeErrorItem runtimeErrorItem) {
            return "runtimeError: " + runtimeErrorItem.message();
        }
        return item.getClass().getSimpleName();
    }
}
