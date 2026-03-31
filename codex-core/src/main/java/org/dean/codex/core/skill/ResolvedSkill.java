package org.dean.codex.core.skill;

import org.dean.codex.protocol.skill.SkillMetadata;

public record ResolvedSkill(SkillMetadata metadata,
                            String instructions) {
}
