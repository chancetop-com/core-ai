package ai.core.server.skill.quality;

import ai.core.server.quality.domain.QualityEnforcement;

/**
 * Immutable identity of one built-in skill rule. The revision is part of every issue and receipt so a later
 * rule change cannot silently reinterpret an old report.
 *
 * @author core-ai
 */
public record SkillRuleMeta(String key, int revision, String name, String requirement, String category,
                            QualityEnforcement enforcement) {
}
