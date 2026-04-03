package org.dean.codex.cli.interactive;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record SlashCommandSpec(
        String name,
        String syntax,
        String description,
        List<String> aliases,
        boolean supportsInlineArgs,
        boolean availableDuringTask) {

    public SlashCommandSpec {
        name = normalizeName(name, "name");
        syntax = normalizeSyntax(syntax);
        description = Objects.requireNonNull(description, "description").trim();
        if (description.isEmpty()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        aliases = normalizeAliases(aliases, name);
    }

    private static String normalizeSyntax(String value) {
        Objects.requireNonNull(value, "syntax");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("syntax must not be blank");
        }
        return normalized;
    }

    public boolean matches(String token) {
        String normalized = normalizeName(token, "token");
        if (name.equals(normalized)) {
            return true;
        }
        for (String alias : aliases) {
            if (alias.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    public Set<String> acceptedTokens() {
        Set<String> tokens = new LinkedHashSet<>();
        tokens.add(name);
        tokens.addAll(aliases);
        return Set.copyOf(tokens);
    }

    public List<String> slashForms() {
        return prefixForms('/');
    }

    private List<String> prefixForms(char prefix) {
        List<String> forms = new ArrayList<>(1 + aliases.size());
        forms.add(prefix + name);
        for (String alias : aliases) {
            forms.add(prefix + alias);
        }
        return List.copyOf(forms);
    }

    private static String normalizeName(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        if (normalized.indexOf(' ') >= 0 || normalized.indexOf('\t') >= 0) {
            throw new IllegalArgumentException(fieldName + " must be a single token");
        }
        if (normalized.startsWith("/") || normalized.startsWith(":")) {
            throw new IllegalArgumentException(fieldName + " must not include a command prefix");
        }
        return normalized;
    }

    private static List<String> normalizeAliases(List<String> aliases, String name) {
        Objects.requireNonNull(aliases, "aliases");
        Set<String> normalized = new LinkedHashSet<>();
        for (String alias : aliases) {
            String normalizedAlias = normalizeName(alias, "alias");
            if (!normalizedAlias.equals(name)) {
                normalized.add(normalizedAlias);
            }
        }
        return List.copyOf(normalized);
    }
}
