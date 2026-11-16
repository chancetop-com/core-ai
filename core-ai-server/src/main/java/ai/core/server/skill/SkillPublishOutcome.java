package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.skill.quality.SkillQualityReport;

/**
 * @author core-ai
 */
public record SkillPublishOutcome(SkillDefinition entity, SkillQualityReport report) {
}
