package ai.core.skill;

import ai.core.tool.ToolSearchScorer;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared scoring function for skill catalog and agent-side skill discovery search. Reuses
 * ToolSearchScorer for standard name/description/namespace weights, adds metadata and
 * allowed-tools hits, and applies the calibrated CORE tier tie-breaker bonus.
 *
 * @author stephen
 */
public final class SkillSearchScorer {
    public static final int METADATA_HIT_SCORE = 5;
    public static final int ALLOWED_TOOL_HIT_SCORE = 5;
    public static final int CORE_TIER_BONUS = 3;
    public static final int LEGACY_CORE_TIER_BONUS = 10;

    public static Integer score(Candidate candidate, List<String> tokens, String query) {
        return score(candidate, tokens, query, CORE_TIER_BONUS);
    }

    public static Integer score(Candidate candidate, List<String> tokens, String query, int coreTierBonus) {
        if (candidate == null) return null;
        if (tokens == null || tokens.isEmpty()) return 0;
        var match = ToolSearchScorer.match(candidate.name(), candidate.description(), candidate.namespace(), query);
        int score = match.score();
        Collection<String> metadataValues = candidate.metadata() == null ? List.of() : candidate.metadata().values();
        List<String> allowedTools = candidate.allowedTools() == null ? List.of() : candidate.allowedTools();
        for (var token : tokens) {
            boolean metadataHit = coversToken(metadataValues, token);
            boolean allowedToolHit = coversToken(allowedTools, token);
            boolean standardHit = coveredByStandardFields(candidate, token);
            if (!standardHit && !metadataHit && !allowedToolHit) {
                return null;
            }
            if (metadataHit) score += METADATA_HIT_SCORE;
            if (allowedToolHit) score += ALLOWED_TOOL_HIT_SCORE;
        }
        if (candidate.coreBonusEligible() && coreTierBonus > 0) {
            score += coreTierBonus;
        }
        return score;
    }

    private static boolean coveredByStandardFields(Candidate candidate, String token) {
        return contains(candidate.namespace(), token)
                || contains(candidate.name(), token)
                || contains(candidate.description(), token);
    }

    private static boolean coversToken(Iterable<String> values, String token) {
        for (var value : values) {
            if (contains(value, token)) return true;
        }
        return false;
    }

    private static boolean contains(String value, String token) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(token);
    }

    private SkillSearchScorer() {
    }

    public record Candidate(
            String namespace,
            String name,
            String description,
            List<String> allowedTools,
            Map<String, String> metadata,
            boolean coreBonusEligible
    ) {
    }
}
