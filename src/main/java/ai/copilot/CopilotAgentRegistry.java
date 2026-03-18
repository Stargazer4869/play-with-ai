package ai.copilot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class CopilotAgentRegistry {

    private final Map<String, CopilotAgentHandler> agentsByMode;
    private final String configuredDefaultMode;

    public CopilotAgentRegistry(List<CopilotAgentHandler> agents,
                                @Value("${copilot.agent-mode:direct}") String configuredDefaultMode) {
        this.agentsByMode = agents.stream()
                .sorted(Comparator.comparing(CopilotAgentHandler::mode))
                .collect(Collectors.toMap(agent -> normalize(agent.mode()),
                        agent -> agent,
                        (existing, replacement) -> {
                            throw new IllegalStateException("Duplicate copilot agent mode: " + existing.mode());
                        },
                        LinkedHashMap::new));
        this.configuredDefaultMode = normalize(configuredDefaultMode);
    }

    public CopilotAgentHandler defaultAgent() {
        return resolve(configuredDefaultMode);
    }

    public CopilotAgentHandler resolve(String mode) {
        if (agentsByMode.isEmpty()) {
            throw new IllegalStateException("No copilot agents are registered.");
        }

        String normalizedMode = normalize(mode);
        if (normalizedMode.isEmpty()) {
            return fallbackAgent();
        }

        return agentsByMode.getOrDefault(normalizedMode, fallbackAgent());
    }

    public Set<String> availableModes() {
        return agentsByMode.keySet();
    }

    private CopilotAgentHandler fallbackAgent() {
        return agentsByMode.getOrDefault("direct", agentsByMode.values().iterator().next());
    }

    private String normalize(String mode) {
        return mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
    }
}

