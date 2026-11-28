package ai.core.server.skill.quality;

import ai.core.server.domain.SkillTier;
import ai.core.server.quality.domain.QualityEnforcement;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compiled-in 12x4 default enforcement matrix for the four tier scopes. Used both to seed policy
 * heads and as the fail-safe fallback when a store is absent or a tier head is missing. Never falls
 * back to the retired skill:server scope.
 *
 * @author stephen
 */
public final class SkillTierPolicyMatrix {
    public static final QualityEnforcement DESCRIPTION_BUDGET_CORE_DEFAULT = QualityEnforcement.WARN;
    public static final QualityEnforcement DESCRIPTION_BUDGET_ALWAYS_ON_DEFAULT = QualityEnforcement.WARN;

    private static final Map<SkillTier, Map<String, QualityEnforcement>> MATRIX = buildMatrix();

    public static QualityEnforcement enforcement(SkillTier tier, String ruleKey) {
        var effectiveTier = tier == null ? SkillTier.DOMAIN : tier;
        var tierMap = MATRIX.get(effectiveTier);
        if (tierMap == null) return QualityEnforcement.SHADOW;
        return tierMap.getOrDefault(ruleKey, QualityEnforcement.SHADOW);
    }

    public static Map<String, QualityEnforcement> forTier(SkillTier tier) {
        var effectiveTier = tier == null ? SkillTier.DOMAIN : tier;
        return Map.copyOf(MATRIX.get(effectiveTier));
    }

    private static Map<SkillTier, Map<String, QualityEnforcement>> buildMatrix() {
        var matrix = new EnumMap<SkillTier, Map<String, QualityEnforcement>>(SkillTier.class);
        for (var tier : SkillTier.values()) {
            matrix.put(tier, new LinkedHashMap<>());
        }
        put(matrix, "SKILL.PACKAGE_HYGIENE",
            QualityEnforcement.BLOCK, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK);
        put(matrix, "SKILL.NAME_IDENTITY",
            QualityEnforcement.BLOCK, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK);
        put(matrix, "SKILL.LINK_REACHABILITY",
            QualityEnforcement.WARN, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK);
        put(matrix, "SKILL.PERMISSION_DECLARATION",
            QualityEnforcement.WARN, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK);
        put(matrix, "SKILL.PERMISSION_UNUSED",
            QualityEnforcement.SHADOW, QualityEnforcement.WARN, QualityEnforcement.WARN, QualityEnforcement.WARN);
        put(matrix, "SKILL.METADATA_REQUIRED",
            QualityEnforcement.WARN, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK);
        put(matrix, "SKILL.VERSION_BUMP_REPO",
            QualityEnforcement.WARN, QualityEnforcement.SHADOW, QualityEnforcement.SHADOW, QualityEnforcement.SHADOW);
        put(matrix, "SKILL.DESCRIPTION_TRIGGER_SCOPE",
            QualityEnforcement.WARN, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK);
        put(matrix, "SKILL.DESCRIPTION_BUDGET",
            QualityEnforcement.SHADOW, QualityEnforcement.WARN,
            DESCRIPTION_BUDGET_CORE_DEFAULT, DESCRIPTION_BUDGET_ALWAYS_ON_DEFAULT);
        put(matrix, "SKILL.PROGRESSIVE_DISCLOSURE",
            QualityEnforcement.SHADOW, QualityEnforcement.WARN, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK);
        put(matrix, "SKILL.UNREFERENCED_REFERENCE",
            QualityEnforcement.SHADOW, QualityEnforcement.WARN, QualityEnforcement.WARN, QualityEnforcement.WARN);
        put(matrix, "SKILL.EVAL_DATASET",
            QualityEnforcement.SHADOW, QualityEnforcement.WARN, QualityEnforcement.BLOCK, QualityEnforcement.BLOCK);
        return matrix;
    }

    private static void put(Map<SkillTier, Map<String, QualityEnforcement>> matrix, String ruleKey,
                            QualityEnforcement imported, QualityEnforcement domain,
                            QualityEnforcement core, QualityEnforcement alwaysOn) {
        matrix.get(SkillTier.IMPORTED).put(ruleKey, imported);
        matrix.get(SkillTier.DOMAIN).put(ruleKey, domain);
        matrix.get(SkillTier.CORE).put(ruleKey, core);
        matrix.get(SkillTier.ALWAYS_ON).put(ruleKey, alwaysOn);
    }

    private SkillTierPolicyMatrix() {
    }
}
