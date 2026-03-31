package org.dean.codex.protocol.appserver;

import org.dean.codex.protocol.skill.SkillMetadata;

import java.util.List;

public record SkillsListResponse(List<SkillMetadata> skills) {

    public SkillsListResponse {
        skills = skills == null ? List.of() : List.copyOf(skills);
    }
}
