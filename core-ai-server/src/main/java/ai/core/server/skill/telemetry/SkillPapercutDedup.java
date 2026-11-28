package ai.core.server.skill.telemetry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic deduplication fingerprint for skill papercuts.
 *
 * @author stephen
 */
public final class SkillPapercutDedup {
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}\\s]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public static String normalizeSummary(String summary) {
        if (summary == null) return "";
        var lower = summary.toLowerCase(Locale.ROOT);
        var stripped = NON_WORD.matcher(lower).replaceAll(" ");
        return WHITESPACE.matcher(stripped).replaceAll(" ").trim();
    }

    public static String dedupKey(String skillId, String packageDigest, SkillPapercutCategory category, String summary) {
        var digest = packageDigest == null ? "" : packageDigest.trim();
        var cat = category == null ? SkillPapercutCategory.OTHER.name() : category.name();
        var payload = skillId + ":" + digest + ":" + cat + ":" + normalizeSummary(summary);
        return sha256(payload);
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private SkillPapercutDedup() {
    }
}
