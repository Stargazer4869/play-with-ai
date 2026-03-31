package org.dean.codex.runtime.springai.skills;

import org.dean.codex.core.skill.ResolvedSkill;
import org.dean.codex.protocol.skill.SkillMetadata;
import org.dean.codex.protocol.skill.SkillScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemSkillServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void discoversWorkspaceAndUserSkillsAndResolvesExplicitMentions() throws Exception {
        Path workspaceSkillsRoot = tempDir.resolve("workspace/.codex/skills");
        Path userSkillsRoot = tempDir.resolve("user/.codex/skills");
        Files.createDirectories(workspaceSkillsRoot.resolve("reviewer"));
        Files.createDirectories(userSkillsRoot.resolve("docs"));

        Files.writeString(workspaceSkillsRoot.resolve("reviewer/SKILL.md"), """
                # reviewer

                Review code for bugs and regressions.
                """);
        Files.writeString(userSkillsRoot.resolve("docs/SKILL.md"), """
                # openai-docs

                Help with official OpenAI documentation.
                """);

        FileSystemSkillService service = new FileSystemSkillService(true, workspaceSkillsRoot, userSkillsRoot);

        List<SkillMetadata> skills = service.listSkills(false);
        List<ResolvedSkill> resolved = service.resolveSkills("Please use $reviewer and $openai-docs", false);

        assertEquals(2, skills.size());
        assertEquals(List.of("openai-docs", "reviewer"), skills.stream().map(SkillMetadata::name).sorted().toList());
        assertEquals(2, resolved.size());
        assertTrue(resolved.stream().anyMatch(skill -> skill.metadata().scope() == SkillScope.WORKSPACE));
        assertTrue(resolved.stream().anyMatch(skill -> skill.instructions().contains("official OpenAI documentation")));
    }
}
