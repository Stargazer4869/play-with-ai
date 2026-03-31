package org.dean.codex.protocol.item;

import org.dean.codex.protocol.conversation.ItemId;
import org.dean.codex.protocol.skill.SkillMetadata;

import java.time.Instant;
import java.util.List;

public record SkillUseItem(ItemId itemId,
                           List<SkillMetadata> skills,
                           Instant createdAt) implements TurnItem {

    public SkillUseItem {
        skills = skills == null ? List.of() : List.copyOf(skills);
    }
}
