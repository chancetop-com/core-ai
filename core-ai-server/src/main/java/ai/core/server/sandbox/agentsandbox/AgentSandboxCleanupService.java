package ai.core.server.sandbox.agentsandbox;

import ai.core.server.sandbox.kubernetes.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * @author stephen
 */
class AgentSandboxCleanupService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentSandboxCleanupService.class);

    private final AgentSandboxClient client;
    private final AgentSandboxExtensionsClient extensionsClient;
    private final KubernetesClient kubernetesClient;

    AgentSandboxCleanupService(AgentSandboxClient client, AgentSandboxExtensionsClient extensionsClient, KubernetesClient kubernetesClient) {
        this.client = client;
        this.extensionsClient = extensionsClient;
        this.kubernetesClient = kubernetesClient;
    }

    void cleanupExpiredSandboxes(int maxLifetimeSeconds) {
        if (extensionsClient != null) {
            cleanupExpiredClaims(maxLifetimeSeconds);
        }
        cleanupExpiredDirectSandboxes(maxLifetimeSeconds);
    }

    private void cleanupExpiredClaims(int maxLifetimeSeconds) {
        try {
            var now = Instant.now();
            var claims = extensionsClient.listClaims("core-ai/component=sandbox");
            for (var claim : claims) {
                cleanupExpiredClaim(claim, now, maxLifetimeSeconds);
            }
        } catch (Exception e) {
            LOGGER.warn("failed to run sandbox claim cleanup", e);
        }
    }

    private void cleanupExpiredClaim(AgentSandboxExtensionsClient.SandboxClaim claim, Instant now, int maxLifetimeSeconds) {
        try {
            if (claim.metadata == null || claim.metadata.creationTimestamp == null) return;
            var deadline = claimDeadline(claim, maxLifetimeSeconds);
            if (now.isAfter(deadline)) {
                LOGGER.info("deleting expired sandbox claim: name={}, deadline={}", claim.getName(), deadline);
                deleteServiceSilently("svc-" + claim.getName());
                extensionsClient.deleteClaim(claim.getName());
            }
        } catch (Exception e) {
            LOGGER.warn("failed to cleanup sandbox claim: {}", claim.getName(), e);
        }
    }

    private Instant claimDeadline(AgentSandboxExtensionsClient.SandboxClaim claim, int maxLifetimeSeconds) {
        if (claim.spec != null && claim.spec.lifecycle != null && claim.spec.lifecycle.shutdownTime != null) {
            try {
                return ZonedDateTime.parse(claim.spec.lifecycle.shutdownTime).toInstant();
            } catch (Exception e) {
                LOGGER.warn("invalid shutdownTime on claim {}: {}", claim.getName(), claim.spec.lifecycle.shutdownTime);
            }
        }
        return ZonedDateTime.parse(claim.metadata.creationTimestamp).toInstant().plus(maxLifetimeSeconds, ChronoUnit.SECONDS);
    }

    private void cleanupExpiredDirectSandboxes(int maxLifetimeSeconds) {
        try {
            var now = Instant.now();
            var sandboxes = client.listSandboxes("core-ai/component=sandbox");
            for (var cr : sandboxes) {
                cleanupExpiredDirectSandbox(cr, now, maxLifetimeSeconds);
            }
        } catch (Exception e) {
            LOGGER.warn("failed to run agent sandbox cleanup", e);
        }
    }

    private void cleanupExpiredDirectSandbox(AgentSandboxClient.SandboxCR cr, Instant now, int maxLifetimeSeconds) {
        try {
            if (cr.metadata == null || cr.metadata.creationTimestamp == null) return;
            var deadline = sandboxDeadline(cr, maxLifetimeSeconds);
            if (now.isAfter(deadline)) {
                LOGGER.info("deleting expired agent sandbox CR: name={}, deadline={}", cr.getName(), deadline);
                deleteServiceSilently("svc-" + cr.getName());
                client.deleteSandbox(cr.getName());
            }
        } catch (Exception e) {
            LOGGER.warn("failed to cleanup agent sandbox CR: {}", cr.getName(), e);
        }
    }

    private Instant sandboxDeadline(AgentSandboxClient.SandboxCR cr, int maxLifetimeSeconds) {
        if (cr.spec != null && cr.spec.shutdownTime != null) {
            try {
                return ZonedDateTime.parse(cr.spec.shutdownTime).toInstant();
            } catch (Exception e) {
                LOGGER.warn("invalid shutdownTime on sandbox CR {}: {}", cr.getName(), cr.spec.shutdownTime);
            }
        }
        return ZonedDateTime.parse(cr.metadata.creationTimestamp).toInstant().plus(maxLifetimeSeconds, ChronoUnit.SECONDS);
    }

    private void deleteServiceSilently(String serviceName) {
        if (kubernetesClient == null) return;
        try {
            kubernetesClient.deleteService(serviceName);
        } catch (Exception e) {
            LOGGER.debug("failed to delete service: {}", serviceName, e);
        }
    }
}
