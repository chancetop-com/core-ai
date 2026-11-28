package ai.core.server.skill;

import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillTier;
import ai.core.server.skill.quality.SkillPublishCandidate;
import ai.core.server.skill.quality.SkillPublishCandidateBuilder;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;

/**
 * Rebuilds the exact publish candidate of an existing skill for one admin rule test. It never writes
 * blobs or skill rows; a fixture is a read-only snapshot plus an optional SKILL.md override.
 *
 * @author core-ai
 */
public class SkillQualityFixtureBuilder {
    @Inject
    SkillBlobStore blobStore;

    public SkillPublishCandidate build(SkillDefinition entity, String contentOverride) {
        return build(entity, contentOverride, SkillTiers.resolve(entity));
    }

    public SkillPublishCandidate build(SkillDefinition entity, String contentOverride, SkillTier targetTier) {
        var content = contentOverride != null && !contentOverride.isBlank() ? contentOverride : entity.content;
        if (content == null) {
            throw new BadRequestException("skill has no SKILL.md content to test against");
        }
        var resources = new SkillResourceWriter(blobStore).bytesOf(entity.resources);
        var directory = entity.directoryName != null ? entity.directoryName : entity.name;
        var effectiveTier = targetTier != null ? targetTier : SkillTiers.resolve(entity);
        return new SkillPublishCandidateBuilder()
            .withTargetTier(effectiveTier)
            .build(entity.namespace, directory, content, resources, entity.sourceType, entity);
    }
}
