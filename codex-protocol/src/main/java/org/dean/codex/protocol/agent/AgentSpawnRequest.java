package org.dean.codex.protocol.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.dean.codex.protocol.conversation.ThreadId;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentSpawnRequest(ThreadId parentThreadId,
                                String taskName,
                                String prompt,
                                String nickname,
                                String role,
                                Integer depth,
                                String modelProvider,
                                String model,
                                String cwd) {

    public AgentSpawnRequest {
        taskName = normalize(taskName);
        prompt = normalize(prompt);
        nickname = normalize(nickname);
        role = normalize(role);
        modelProvider = normalize(modelProvider);
        model = normalize(model);
        cwd = normalize(cwd);
    }

    private static String normalize(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
