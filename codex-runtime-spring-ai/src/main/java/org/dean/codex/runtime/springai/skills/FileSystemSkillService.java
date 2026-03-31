package org.dean.codex.runtime.springai.skills;

import org.dean.codex.core.skill.ResolvedSkill;
import org.dean.codex.core.skill.SkillService;
import org.dean.codex.protocol.skill.SkillMetadata;
import org.dean.codex.protocol.skill.SkillScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class FileSystemSkillService implements SkillService {

    private static final Pattern SKILL_REFERENCE_PATTERN = Pattern.compile("\\$([A-Za-z0-9._-]+)");

    private final boolean enabled;
    private final Path workspaceSkillsRoot;
    private final Path userSkillsRoot;

    public FileSystemSkillService(boolean enabled, Path workspaceSkillsRoot, Path userSkillsRoot) {
        this.enabled = enabled;
        this.workspaceSkillsRoot = workspaceSkillsRoot == null ? null : workspaceSkillsRoot.toAbsolutePath().normalize();
        this.userSkillsRoot = userSkillsRoot == null ? null : userSkillsRoot.toAbsolutePath().normalize();
    }

    @Override
    public List<SkillMetadata> listSkills(boolean forceReload) {
        return loadSkillFiles().stream()
                .map(LoadedSkillFile::metadata)
                .toList();
    }

    @Override
    public List<ResolvedSkill> resolveSkills(String input, boolean forceReload) {
        if (!enabled || input == null || input.isBlank()) {
            return List.of();
        }

        Set<String> referencedNames = extractReferencedSkillNames(input);
        if (referencedNames.isEmpty()) {
            return List.of();
        }

        return loadSkillFiles().stream()
                .filter(skill -> referencedNames.contains(skill.metadata().name().toLowerCase(Locale.ROOT)))
                .map(skill -> new ResolvedSkill(skill.metadata(), skill.instructions()))
                .toList();
    }

    private List<LoadedSkillFile> loadSkillFiles() {
        if (!enabled) {
            return List.of();
        }

        Map<String, LoadedSkillFile> skillsByName = new LinkedHashMap<>();
        collectSkills(skillsByName, workspaceSkillsRoot, SkillScope.WORKSPACE);
        collectSkills(skillsByName, userSkillsRoot, SkillScope.USER);
        return skillsByName.values().stream()
                .sorted(Comparator.comparing((LoadedSkillFile skill) -> skill.metadata().scope())
                        .thenComparing(skill -> skill.metadata().name().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private void collectSkills(Map<String, LoadedSkillFile> skillsByName, Path root, SkillScope scope) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }

        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(path -> path.getFileName().toString().equals("SKILL.md"))
                    .sorted()
                    .forEach(path -> loadSkill(path, scope).ifPresent(skill ->
                            skillsByName.putIfAbsent(skill.metadata().name().toLowerCase(Locale.ROOT), skill)));
        }
        catch (IOException ignored) {
            // Invalid skill roots are ignored in this first foundation pass.
        }
    }

    private java.util.Optional<LoadedSkillFile> loadSkill(Path skillFile, SkillScope scope) {
        try {
            String content = Files.readString(skillFile);
            if (content.isBlank()) {
                return java.util.Optional.empty();
            }

            String name = extractName(skillFile, content);
            String description = extractDescription(content);
            String shortDescription = description.isBlank()
                    ? ""
                    : description.lines().findFirst().orElse(description);
            SkillMetadata metadata = new SkillMetadata(
                    name,
                    description.isBlank() ? "No description provided." : description,
                    shortDescription,
                    skillFile.toAbsolutePath().normalize().toString(),
                    scope,
                    true);
            return java.util.Optional.of(new LoadedSkillFile(metadata, content));
        }
        catch (IOException ignored) {
            return java.util.Optional.empty();
        }
    }

    private String extractName(Path skillFile, String content) {
        for (String line : content.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).trim();
            }
        }
        Path parent = skillFile.getParent();
        return parent == null ? skillFile.getFileName().toString() : parent.getFileName().toString();
    }

    private String extractDescription(String content) {
        List<String> paragraphs = new ArrayList<>();
        boolean seenHeading = false;
        for (String line : content.lines().toList()) {
            String trimmed = line.trim();
            if (!seenHeading && trimmed.startsWith("# ")) {
                seenHeading = true;
                continue;
            }
            if (!seenHeading && trimmed.isBlank()) {
                continue;
            }
            if (trimmed.isBlank()) {
                if (!paragraphs.isEmpty()) {
                    break;
                }
                continue;
            }
            if (trimmed.startsWith("#")) {
                continue;
            }
            paragraphs.add(trimmed);
        }
        return String.join(" ", paragraphs).trim();
    }

    private Set<String> extractReferencedSkillNames(String input) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = SKILL_REFERENCE_PATTERN.matcher(input);
        while (matcher.find()) {
            names.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private record LoadedSkillFile(SkillMetadata metadata, String instructions) {
    }
}
