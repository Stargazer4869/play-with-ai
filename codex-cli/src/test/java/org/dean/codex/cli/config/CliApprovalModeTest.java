package org.dean.codex.cli.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CliApprovalModeTest {

    @Test
    void parsesApprovalModesCaseInsensitively() {
        assertEquals(CliApprovalMode.REVIEW_SENSITIVE, CliApprovalMode.fromCliValue("review-sensitive"));
        assertEquals(CliApprovalMode.REVIEW_SENSITIVE, CliApprovalMode.fromCliValue("Review_Sensitive"));
        assertEquals(CliApprovalMode.AUTO, CliApprovalMode.fromCliValue("AUTO"));
        assertEquals(CliApprovalMode.FULL_AUTO, CliApprovalMode.fromCliValue("full_auto"));
    }

    @Test
    void returnsNullForBlankApprovalMode() {
        assertNull(CliApprovalMode.fromCliValue("   "));
        assertNull(CliApprovalMode.fromCliValue(null));
    }

    @Test
    void rejectsUnknownApprovalModes() {
        assertThrows(IllegalArgumentException.class, () -> CliApprovalMode.fromCliValue("manual"));
    }
}
