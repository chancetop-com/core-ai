package ai.core.cli.hub.skill;

import ai.core.skill.SkillMetadata;
import ai.core.skill.telemetry.SkillTelemetryIdentity;

import java.nio.file.Path;
import java.util.function.Function;

/**
 * Resolves a local skill to a telemetry identity only when its directory carries a .skill-hub.json
 * marker linked to the active server. Unlinked local private skills resolve to null and are never reported.
 *
 * @author stephen
 */
public class CliSkillIdentityResolver implements Function<SkillMetadata, SkillTelemetryIdentity> {
    private final String normalizedServerUrl;

    public CliSkillIdentityResolver(String serverUrl) {
        this.normalizedServerUrl = SkillHubMarker.normalizeServer(serverUrl);
    }

    @Override
    public SkillTelemetryIdentity apply(SkillMetadata skill) {
        if (skill == null || skill.getSkillDir() == null || skill.getSkillDir().isBlank()) {
            return null;
        }
        try {
            var marker = SkillHubMarker.load(Path.of(skill.getSkillDir()));
            if (marker == null || !marker.isManaged()) {
                return null;
            }
            if (normalizedServerUrl != null) {
                var markerServer = SkillHubMarker.normalizeServer(marker.server());
                if (markerServer != null && !normalizedServerUrl.equals(markerServer)) {
                    return null;
                }
            }
            var digest = marker.digest() != null ? marker.digest() : "";
            return new SkillTelemetryIdentity(marker.id(), marker.qualifiedName(), digest);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
