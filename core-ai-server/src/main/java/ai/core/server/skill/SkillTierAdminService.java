package ai.core.server.skill;

import ai.core.api.server.skill.SkillDefinitionView;
import ai.core.api.server.skill.UpdateSkillTierRequest;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillTier;
import ai.core.server.quality.domain.QualityGateResult;
import ai.core.server.skill.quality.SkillQualityGate;
import ai.core.server.skill.quality.SkillQualityGateException;
import ai.core.server.skill.quality.SkillQualityPolicyService;
import ai.core.server.skill.quality.SkillQualityReport;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;

/**
 * Handles human platform admin promotions and demotions across the four governance tiers
 * (IMPORTED, DOMAIN, CORE, ALWAYS_ON), enforcing tier-specific quality gates, the global
 * ALWAYS_ON quota, demotion reasons, and structured audit logging.
 *
 * @author stephen
 */
public class SkillTierAdminService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillTierAdminService.class);
    public static final int DEFAULT_MAX_ALWAYS_ON_SKILLS = 10;
    public static final String QUOTA_EXCEEDED_CODE = "ALWAYS_ON_QUOTA_EXCEEDED";

    @Inject
    SkillStore skillStore;
    @Inject
    SkillQualityPolicyService policyService;
    @Inject
    SkillQualityGate gate;
    @Inject
    SkillQualityFixtureBuilder fixtureBuilder;
    @Inject
    SkillService skillService;

    private int maxAlwaysOnSkills = DEFAULT_MAX_ALWAYS_ON_SKILLS;

    public void setMaxAlwaysOnSkills(int maxAlwaysOnSkills) {
        if (maxAlwaysOnSkills > 0) this.maxAlwaysOnSkills = maxAlwaysOnSkills;
    }

    public int maxAlwaysOnSkills() {
        return maxAlwaysOnSkills;
    }

    public SkillDefinitionView updateTier(String skillId, UpdateSkillTierRequest request, String actorUserId) {
        policyService.requireAdmin(actorUserId);
        if (request == null || request.tier == null) {
            throw new BadRequestException("tier is required");
        }
        var entity = skillStore.get(skillId)
            .orElseThrow(() -> new NotFoundException("skill not found, id=" + skillId));
        var fromTier = SkillTiers.resolve(entity);
        var toTier = SkillTiers.fromView(request.tier);
        if (fromTier == toTier) {
            return SkillViewMapper.toView(entity);
        }
        if (fromTier == SkillTier.IMPORTED && toTier == SkillTier.ALWAYS_ON) {
            throw new BadRequestException("IMPORTED skills cannot be promoted directly to ALWAYS_ON; promote to CORE first");
        }
        var reason = request.reason == null ? "" : request.reason.trim();
        if (isDemotion(fromTier, toTier) && reason.isEmpty()) {
            throw new BadRequestException("reason is required when demoting a skill");
        }
        if (toTier == SkillTier.CORE) {
            verifyGateForTier(entity, SkillTier.CORE);
        } else if (toTier == SkillTier.ALWAYS_ON) {
            verifyGateForTier(entity, SkillTier.CORE);
            verifyGateForTier(entity, SkillTier.ALWAYS_ON);
            enforceAlwaysOnQuota();
        }

        int expectedRevision = entity.revision == null ? 0 : entity.revision;
        entity.tier = toTier;
        entity.revision = expectedRevision + 1;
        entity.updatedAt = ZonedDateTime.now();
        if (!skillStore.replaceIfRevision(entity, expectedRevision)) {
            throw SkillQualityGateException.stale("The skill changed concurrently; refresh and retry.");
        }
        skillService.invalidateCatalogCache();
        auditTierChange(actorUserId, entity, fromTier, toTier, reason);
        return SkillViewMapper.toView(entity);
    }

    private void verifyGateForTier(SkillDefinition entity, SkillTier targetTier) {
        var candidate = fixtureBuilder.build(entity, null, targetTier);
        var result = gate.evaluate(candidate);
        if (!result.passed()) {
            var report = SkillQualityReport.of(candidate, result);
            throw result.gateResult() == QualityGateResult.REJECTED
                ? SkillQualityGateException.rejected(report)
                : SkillQualityGateException.unavailable(report);
        }
    }

    private void enforceAlwaysOnQuota() {
        long activeCount = skillStore.countByTier(SkillTier.ALWAYS_ON);
        if (activeCount >= maxAlwaysOnSkills) {
            throw new ConflictException(
                "ALWAYS_ON skill quota exceeded (" + activeCount + "/" + maxAlwaysOnSkills + "); demote another skill first.",
                QUOTA_EXCEEDED_CODE);
        }
    }

    private boolean isDemotion(SkillTier from, SkillTier to) {
        return rank(to) < rank(from);
    }

    private int rank(SkillTier tier) {
        return switch (tier) {
            case IMPORTED -> 0;
            case DOMAIN -> 1;
            case CORE -> 2;
            case ALWAYS_ON -> 3;
        };
    }

    private void auditTierChange(String actorUserId, SkillDefinition entity, SkillTier fromTier,
                                 SkillTier toTier, String reason) {
        LOGGER.info("skill tier audit: actorUserId={}, skillId={}, qualifiedName={}, fromTier={}, toTier={}, digest={}, reason={}",
            actorUserId, entity.id, entity.qualifiedName, fromTier, toTier, entity.digest, reason);
        ActionLogContext.put("audit_event", "skill_tier_change");
        ActionLogContext.put("audit_actor_user_id", actorUserId);
        ActionLogContext.put("audit_skill_id", entity.id);
        ActionLogContext.put("audit_qualified_name", entity.qualifiedName);
        ActionLogContext.put("audit_from_tier", fromTier.name());
        ActionLogContext.put("audit_to_tier", toTier.name());
        ActionLogContext.put("audit_digest", entity.digest);
        ActionLogContext.put("audit_reason", reason);
    }
}
