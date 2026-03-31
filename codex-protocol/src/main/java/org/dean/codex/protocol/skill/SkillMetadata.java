package org.dean.codex.protocol.skill;

public record SkillMetadata(String name,
                            String description,
                            String shortDescription,
                            String path,
                            SkillScope scope,
                            boolean enabled) {
}
