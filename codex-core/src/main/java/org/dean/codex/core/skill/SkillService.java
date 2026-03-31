package org.dean.codex.core.skill;

import org.dean.codex.protocol.skill.SkillMetadata;

import java.util.List;

public interface SkillService {

    List<SkillMetadata> listSkills(boolean forceReload);

    List<ResolvedSkill> resolveSkills(String input, boolean forceReload);
}
