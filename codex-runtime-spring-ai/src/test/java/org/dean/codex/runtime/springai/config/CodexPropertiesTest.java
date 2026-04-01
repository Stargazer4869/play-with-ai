package org.dean.codex.runtime.springai.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodexPropertiesTest {

    @Test
    void defaultsReflectUnifiedAgentAndModelSettings() {
        CodexProperties properties = new CodexProperties();

        assertEquals(100, properties.getAgent().getMaxSteps());
        assertEquals(3, properties.getAgent().getMaxActionsPerStep());
        assertEquals(8, properties.getAgent().getHistoryWindow());
        assertEquals(4, properties.getAgent().getMaxDepth());
        assertEquals(272_000, properties.getModel().getContextWindow());
        assertEquals(200_000, properties.getModel().getAutoCompactTokenLimit());
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyMaxActionsPerTurnAliasDelegatesToPerStepSetting() {
        CodexProperties properties = new CodexProperties();

        properties.getAgent().setMaxActionsPerTurn(5);

        assertEquals(5, properties.getAgent().getMaxActionsPerStep());
        assertEquals(5, properties.getAgent().getMaxActionsPerTurn());
    }
}
