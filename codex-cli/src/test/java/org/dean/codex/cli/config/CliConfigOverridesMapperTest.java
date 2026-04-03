package org.dean.codex.cli.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliConfigOverridesMapperTest {

    @Test
    void normalizesRawValuesIntoTypedOverrides() {
        CliConfigOverrides overrides = CliConfigOverridesMapper.fromRawValues(
                " gpt-5.4 ",
                " ./workspace ",
                "workspace_write",
                "review_sensitive");

        assertEquals("gpt-5.4", overrides.model());
        assertEquals("./workspace", overrides.cd());
        assertEquals(CliSandboxMode.WORKSPACE_WRITE, overrides.sandbox());
        assertEquals(CliApprovalMode.REVIEW_SENSITIVE, overrides.approvalMode());
        assertFalse(overrides.isEmpty());
    }

    @Test
    void treatsBlankInputsAsMissingOverrides() {
        CliConfigOverrides overrides = CliConfigOverridesMapper.fromRawValues(
                "   ",
                null,
                "  ",
                null);

        assertNull(overrides.model());
        assertNull(overrides.cd());
        assertNull(overrides.sandbox());
        assertNull(overrides.approvalMode());
        assertTrue(overrides.isEmpty());
    }

    @Test
    void mergesOverridesOnTopOfBaseValues() {
        CliConfigOverrides base = new CliConfigOverrides("gpt-5.4", "/base", CliSandboxMode.READ_ONLY, CliApprovalMode.AUTO);
        CliConfigOverrides overrides = new CliConfigOverrides(null, "/override", CliSandboxMode.FULL_ACCESS, null);

        CliConfigOverrides merged = CliConfigOverridesMapper.merge(base, overrides);

        assertEquals("gpt-5.4", merged.model());
        assertEquals("/override", merged.cd());
        assertEquals(CliSandboxMode.FULL_ACCESS, merged.sandbox());
        assertEquals(CliApprovalMode.AUTO, merged.approvalMode());
    }

    @Test
    void emptyMapperResultHasNoOverrides() {
        CliConfigOverrides empty = CliConfigOverridesMapper.empty();

        assertTrue(empty.isEmpty());
        assertNull(empty.model());
        assertNull(empty.cd());
        assertNull(empty.sandbox());
        assertNull(empty.approvalMode());
    }
}
