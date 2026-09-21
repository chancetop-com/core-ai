package ai.core.server.sandbox.agentsandbox;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the SandboxClaim manifest. A claim only carries template, warm pool and lifecycle — the pod
 * spec of the assigned sandbox comes from the pool template, which is why sessions with a custom image
 * or env cannot be served from the warm pool and are created as direct Sandbox CRs instead.
 *
 * @author stephen
 */
public class SandboxClaimSpecBuilder {
    private static final String API_GROUP = "extensions.agents.x-k8s.io";
    private static final String API_VERSION = "v1alpha1";

    private final String claimName;
    private final String templateName;
    private final String warmPoolName;
    private final Owner owner;
    private final int lifetimeSeconds;

    public SandboxClaimSpecBuilder(String claimName, String templateName, String warmPoolName, Owner owner, int lifetimeSeconds) {
        this.claimName = claimName;
        this.templateName = templateName;
        this.warmPoolName = warmPoolName;
        this.owner = owner;
        this.lifetimeSeconds = lifetimeSeconds;
    }

    public Map<String, Object> build() {
        var cr = new LinkedHashMap<String, Object>();
        cr.put("apiVersion", API_GROUP + "/" + API_VERSION);
        cr.put("kind", "SandboxClaim");

        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("name", claimName);
        metadata.put("labels", Map.of(
                "app.kubernetes.io/managed-by", "core-ai",
                "core-ai/component", "sandbox",
                "core-ai/session-id", sanitizeLabel(owner.sessionId() != null ? owner.sessionId() : "unknown"),
                "core-ai/user-id", sanitizeLabel(owner.userId() != null ? owner.userId() : "unknown")
        ));
        cr.put("metadata", metadata);

        var spec = new LinkedHashMap<String, Object>();
        spec.put("sandboxTemplateRef", Map.of("name", templateName));
        spec.put("warmpool", warmPoolName);

        var lifecycle = new LinkedHashMap<String, Object>();
        lifecycle.put("shutdownPolicy", "Delete");
        lifecycle.put("shutdownTime", Instant.now().plus(lifetimeSeconds, ChronoUnit.SECONDS).toString());
        spec.put("lifecycle", lifecycle);

        cr.put("spec", spec);
        return cr;
    }

    private String sanitizeLabel(String value) {
        var sanitized = value.replaceAll("[^A-Za-z0-9_.\\-]", "_");
        if (sanitized.length() > 63) sanitized = sanitized.substring(0, 63);
        sanitized = sanitized.replaceAll("^[^A-Za-z0-9]+", "");
        sanitized = sanitized.replaceAll("[^A-Za-z0-9]+$", "");
        return sanitized.isEmpty() ? "unknown" : sanitized;
    }

    public record Owner(String sessionId, String userId) {
    }
}
