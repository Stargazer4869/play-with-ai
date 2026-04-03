package org.dean.codex.cli.interactive;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class SlashCommandRegistry {
    private final List<SlashCommandSpec> commands;
    private final Map<String, SlashCommandSpec> byToken;

    private SlashCommandRegistry(List<SlashCommandSpec> commands) {
        this.commands = List.copyOf(commands);
        this.byToken = index(commands);
    }

    public static SlashCommandRegistry defaultRegistry() {
        return builder()
                .add(new SlashCommandSpec("help", "/help", "Show this help", List.of(), false, true))
                .add(new SlashCommandSpec("new", "/new", "Start a new thread", List.of(), false, false))
                .add(new SlashCommandSpec("threads", "/threads [all|loaded|archived]", "List threads", List.of(), false, true))
                .add(new SlashCommandSpec("resume", "/resume <thread-id-prefix>", "Switch to a thread", List.of("use"), true, false))
                .add(new SlashCommandSpec("fork", "/fork [thread-id-prefix] [title]", "Fork the active or matching thread", List.of(), true, false))
                .add(new SlashCommandSpec("archive", "/archive [thread-id-prefix]", "Archive a thread", List.of(), true, true))
                .add(new SlashCommandSpec("unarchive", "/unarchive <thread-id-prefix>", "Restore an archived thread", List.of(), true, true))
                .add(new SlashCommandSpec("rollback", "/rollback [thread-id-prefix] <turn-count>", "Trim turns from a thread", List.of(), true, true))
                .add(new SlashCommandSpec("subagents", "/subagents [thread-id-prefix]", "Show the related thread tree", List.of(), true, true))
                .add(new SlashCommandSpec("agent", "/agent <tree|use> ...", "Navigate the current thread tree", List.of(), true, true))
                .add(new SlashCommandSpec("skills", "/skills", "List discovered skills", List.of(), false, true))
                .add(new SlashCommandSpec("history", "/history", "Show the active thread history", List.of(), false, true))
                .add(new SlashCommandSpec("compact", "/compact", "Compact the active thread", List.of(), false, true))
                .add(new SlashCommandSpec("approvals", "/approvals", "List pending approvals", List.of(), false, true))
                .add(new SlashCommandSpec("approve", "/approve <approval-id-prefix>", "Approve a pending command", List.of(), true, true))
                .add(new SlashCommandSpec("reject", "/reject <approval-id-prefix> [reason]", "Reject a pending command", List.of(), true, true))
                .add(new SlashCommandSpec("interrupt", "/interrupt", "Interrupt the active turn", List.of(), false, true))
                .add(new SlashCommandSpec("steer", "/steer <message>", "Send steering guidance to the active turn", List.of(), true, true))
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<SlashCommandSpec> commands() {
        return commands;
    }

    public Optional<SlashCommandSpec> find(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String normalized = token.trim();
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        char first = normalized.charAt(0);
        if (first == SlashCommandPrefix.SLASH.prefixChar()) {
            normalized = normalized.substring(1).trim();
            if (normalized.isEmpty()) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(byToken.get(normalized));
    }

    public Optional<SlashCommandSpec> findByName(String name) {
        return find(name);
    }

    public Optional<SlashCommandSpec> findByAlias(String alias) {
        return find(alias);
    }

    public List<String> helpLines() {
        List<String> lines = new ArrayList<>(commands.size());
        for (SlashCommandSpec command : commands) {
            lines.add(renderHelpLine(command));
        }
        return List.copyOf(lines);
    }

    public String renderHelpLine(SlashCommandSpec command) {
        Objects.requireNonNull(command, "command");
        StringBuilder builder = new StringBuilder();
        builder.append(command.syntax());
        if (!command.aliases().isEmpty()) {
            builder.append(" (aliases: ");
            for (int i = 0; i < command.aliases().size(); i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append('/').append(command.aliases().get(i));
            }
            builder.append(')');
        }
        builder.append(" - ").append(command.description());
        return builder.toString();
    }

    private static Map<String, SlashCommandSpec> index(Collection<SlashCommandSpec> commands) {
        Map<String, SlashCommandSpec> indexed = new LinkedHashMap<>();
        for (SlashCommandSpec command : commands) {
            indexCommand(indexed, command.name(), command);
            for (String alias : command.aliases()) {
                indexCommand(indexed, alias, command);
            }
        }
        return Map.copyOf(indexed);
    }

    private static void indexCommand(Map<String, SlashCommandSpec> indexed, String token, SlashCommandSpec command) {
        SlashCommandSpec existing = indexed.putIfAbsent(token, command);
        if (existing != null && existing != command) {
            throw new IllegalArgumentException("Duplicate slash command token: " + token);
        }
    }

    public static final class Builder {
        private final List<SlashCommandSpec> commands = new ArrayList<>();

        private Builder() {
        }

        public Builder add(SlashCommandSpec command) {
            commands.add(Objects.requireNonNull(command, "command"));
            return this;
        }

        public SlashCommandRegistry build() {
            return new SlashCommandRegistry(commands);
        }
    }
}
