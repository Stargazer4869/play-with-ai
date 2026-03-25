package org.dean.codex.tools.local;

import org.dean.codex.core.tool.local.CommandApprovalPolicy;
import org.dean.codex.protocol.tool.CommandApproval;
import org.dean.codex.protocol.tool.CommandApprovalDecision;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class PatternCommandApprovalPolicy implements CommandApprovalPolicy {

    public enum Mode {
        ALLOW_ALL,
        REVIEW_SENSITIVE,
        BLOCK_ALL;

        public static Mode from(String value) {
            if (value == null || value.isBlank()) {
                return REVIEW_SENSITIVE;
            }
            String normalized = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
            return Mode.valueOf(normalized);
        }
    }

    private static final List<Pattern> BLOCK_PATTERNS = List.of(
            Pattern.compile("(^|\\b)sudo\\b"),
            Pattern.compile("(^|\\b)rm\\s+-rf\\b"),
            Pattern.compile("(^|\\b)shutdown\\b"),
            Pattern.compile("(^|\\b)reboot\\b"),
            Pattern.compile("(^|\\b)mkfs\\b"),
            Pattern.compile("(^|\\b)dd\\s+if="),
            Pattern.compile(":\\(\\)\\s*\\{")
    );

    private static final List<Pattern> REVIEW_PATTERNS = List.of(
            Pattern.compile("(^|\\b)git\\s+(commit|push|rebase|reset|clean|checkout|switch|merge|cherry-pick)\\b"),
            Pattern.compile("(^|\\b)(rm|mv|cp|chmod|chown)\\b"),
            Pattern.compile("(^|\\b)(curl|wget|ssh|scp|rsync)\\b"),
            Pattern.compile("(^|\\b)(docker|podman|kubectl|helm)\\b"),
            Pattern.compile("(^|\\b)(npm|pnpm|yarn|brew)\\s+(install|add|update|upgrade|remove|uninstall)\\b"),
            Pattern.compile("(^|\\b)mvn\\s+(deploy|release:|versions:|dependency:purge-local-repository)\\b"),
            Pattern.compile("(^|\\b)gradle\\s+(publish|release|wrapper)\\b"),
            Pattern.compile("(^|\\b)(pip|uv|poetry)\\s+(install|add|remove|sync|lock)\\b"),
            Pattern.compile("(^|\\b)tee\\b")
    );

    private final Mode mode;

    public PatternCommandApprovalPolicy(Mode mode) {
        this.mode = mode == null ? Mode.REVIEW_SENSITIVE : mode;
    }

    @Override
    public CommandApproval evaluate(String command) {
        if (command == null || command.isBlank()) {
            return new CommandApproval(CommandApprovalDecision.BLOCK, "Command must not be blank.");
        }

        if (mode == Mode.BLOCK_ALL) {
            return new CommandApproval(CommandApprovalDecision.BLOCK, "Shell execution is disabled by configuration.");
        }

        String normalized = command.trim();
        for (Pattern pattern : BLOCK_PATTERNS) {
            if (pattern.matcher(normalized).find()) {
                return new CommandApproval(
                        CommandApprovalDecision.BLOCK,
                        "Blocked because the command matches a dangerous shell pattern.");
            }
        }

        if (mode == Mode.ALLOW_ALL) {
            return new CommandApproval(CommandApprovalDecision.ALLOW, "Allowed by configuration.");
        }

        for (Pattern pattern : REVIEW_PATTERNS) {
            if (pattern.matcher(normalized).find()) {
                return new CommandApproval(
                        CommandApprovalDecision.REQUIRE_APPROVAL,
                        "This command may mutate the system, workspace, or network state.");
            }
        }

        return new CommandApproval(CommandApprovalDecision.ALLOW, "Allowed as a routine local command.");
    }
}
