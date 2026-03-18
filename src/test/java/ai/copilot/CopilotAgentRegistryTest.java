package ai.copilot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

class CopilotAgentRegistryTest {

    @Test
    void resolvesConfiguredDefaultMode() {
        CopilotAgentRegistry registry = new CopilotAgentRegistry(List.of(new StubAgent("direct"), new StubAgent("react")), "react");

        assertEquals("react", registry.defaultAgent().mode());
    }

    @Test
    void fallsBackToDirectWhenRequestedModeIsUnknown() {
        CopilotAgentRegistry registry = new CopilotAgentRegistry(List.of(new StubAgent("react"), new StubAgent("direct")), "unknown");

        assertEquals("direct", registry.resolve("missing").mode());
    }

    @Test
    void listsAvailableModesInSortedOrder() {
        CopilotAgentRegistry registry = new CopilotAgentRegistry(List.of(new StubAgent("react"), new StubAgent("direct")), "direct");

        assertIterableEquals(List.of("direct", "react"), registry.availableModes());
    }

    private record StubAgent(String mode) implements CopilotAgentHandler {
        @Override
        public String handleUserInput(String input) {
            return mode + ": " + input;
        }
    }
}

