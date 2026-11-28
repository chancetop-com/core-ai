package ai.core.server.skill;

import ai.core.api.server.skill.SkillTierView;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.SkillSourceType;
import ai.core.server.domain.SkillTier;
import core.framework.web.exception.BadRequestException;

import java.util.List;
import java.util.Locale;

/**
 * Normalization, scope lookup, and view conversion helpers for SkillTier.
 *
 * @author stephen
 */
public final class SkillTiers {
    public static final List<String> ALL_SCOPES = List.of(
        SkillTier.SCOPE_IMPORTED,
        SkillTier.SCOPE_DOMAIN,
        SkillTier.SCOPE_CORE,
        SkillTier.SCOPE_ALWAYS_ON
    );

    public static SkillTier resolve(SkillDefinition definition) {
        if (definition == null) return SkillTier.DOMAIN;
        if (definition.tier != null) return definition.tier;
        if ("builtin".equals(definition.namespace) && "system".equals(definition.userId)) {
            return SkillTier.CORE;
        }
        if (definition.sourceType == SkillSourceType.REPO || definition.repoConfig != null) {
            return SkillTier.IMPORTED;
        }
        return SkillTier.DOMAIN;
    }

    public static SkillTier defaultFor(SkillSourceType sourceType, SkillDefinition existing) {
        if (existing != null) return resolve(existing);
        return sourceType == SkillSourceType.REPO ? SkillTier.IMPORTED : SkillTier.DOMAIN;
    }

    public static SkillTier tierForScope(String scope) {
        if (scope == null) return null;
        return switch (scope.trim().toLowerCase(Locale.ROOT)) {
            case SkillTier.SCOPE_IMPORTED -> SkillTier.IMPORTED;
            case SkillTier.SCOPE_DOMAIN -> SkillTier.DOMAIN;
            case SkillTier.SCOPE_CORE -> SkillTier.CORE;
            case SkillTier.SCOPE_ALWAYS_ON -> SkillTier.ALWAYS_ON;
            default -> null;
        };
    }

    public static SkillTier requireTierForScope(String scope) {
        var tier = tierForScope(scope);
        if (tier == null) {
            throw new BadRequestException("invalid skill quality policy scope: " + scope);
        }
        return tier;
    }

    public static String requireScope(String scope) {
        return requireTierForScope(scope).scope();
    }

    public static SkillTierView toView(SkillTier tier) {
        if (tier == null) return null;
        return switch (tier) {
            case IMPORTED -> SkillTierView.IMPORTED;
            case DOMAIN -> SkillTierView.DOMAIN;
            case CORE -> SkillTierView.CORE;
            case ALWAYS_ON -> SkillTierView.ALWAYS_ON;
        };
    }

    public static SkillTier fromView(SkillTierView view) {
        if (view == null) return null;
        return switch (view) {
            case IMPORTED -> SkillTier.IMPORTED;
            case DOMAIN -> SkillTier.DOMAIN;
            case CORE -> SkillTier.CORE;
            case ALWAYS_ON -> SkillTier.ALWAYS_ON;
        };
    }

    private SkillTiers() {
    }
}
