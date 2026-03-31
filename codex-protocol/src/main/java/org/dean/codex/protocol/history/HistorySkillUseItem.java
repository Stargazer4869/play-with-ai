package org.dean.codex.protocol.history;

import org.dean.codex.protocol.skill.SkillMetadata;
import org.dean.codex.protocol.conversation.TurnId;

import java.time.Instant;
import java.util.List;

public record HistorySkillUseItem(TurnId turnId,
                                  List<SkillMetadata> skills,
                                  Instant createdAt) implements ThreadHistoryItem {

    public HistorySkillUseItem(List<SkillMetadata> skills, Instant createdAt) {
        this(new TurnId(""), skills, createdAt);
    }

    public HistorySkillUseItem {
        turnId = turnId == null ? new TurnId("") : turnId;
        skills = skills == null ? List.of() : List.copyOf(skills);
    }
}
