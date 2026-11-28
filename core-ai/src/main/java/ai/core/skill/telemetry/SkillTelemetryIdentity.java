package ai.core.skill.telemetry;

/**
 * Stable identity for a skill telemetry bucket. Hub-backed skills carry skillId; filesystem
 * skills without a server id fall back to qualifiedName combined with packageDigest.
 *
 * @author stephen
 */
public record SkillTelemetryIdentity(String skillId, String qualifiedName, String packageDigest) {
    public String bucketKey() {
        var digest = packageDigest == null ? "" : packageDigest;
        if (skillId != null && !skillId.isBlank()) {
            return "id:" + skillId.trim() + "\0" + digest;
        }
        var name = qualifiedName == null ? "" : qualifiedName.trim();
        return "qn:" + name + "\0" + digest;
    }

    public boolean isValid() {
        boolean hasId = skillId != null && !skillId.isBlank();
        boolean hasName = qualifiedName != null && !qualifiedName.isBlank();
        return hasId || hasName;
    }
}
