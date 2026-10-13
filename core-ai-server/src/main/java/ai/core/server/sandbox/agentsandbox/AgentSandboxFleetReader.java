package ai.core.server.sandbox.agentsandbox;

import ai.core.server.sandbox.SandboxFleet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

/**
 * Reads the cluster-wide sandbox fleet from Kubernetes for the runtime stats. Extracted from
 * {@link AgentSandboxProvider} (file-size budget); read failures are logged and reported as zero rather
 * than failing the stats call, because an unreadable cluster is itself visible from the missing numbers.
 *
 * @author stephen
 */
public class AgentSandboxFleetReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentSandboxFleetReader.class);
    private static final String DIRECT_CR_LABEL_SELECTOR = "core-ai/component=sandbox";

    private final AgentSandboxClient client;
    private final AgentSandboxExtensionsClient extensionsClient;

    public AgentSandboxFleetReader(AgentSandboxClient client, AgentSandboxExtensionsClient extensionsClient) {
        this.client = client;
        this.extensionsClient = extensionsClient;
    }

    public SandboxFleet read() {
        var fleet = new SandboxFleet();
        readClaims(fleet);
        readDirectCrs(fleet);
        return fleet;
    }

    private void readClaims(SandboxFleet fleet) {
        if (extensionsClient == null) return;
        try {
            var claims = extensionsClient.listClaims(null);
            fleet.claims = claims.size();
            fleet.oldestClaimAgeSeconds = oldestAgeSeconds(claims.stream()
                    .map(claim -> claim.metadata == null ? null : claim.metadata.creationTimestamp).toList());
        } catch (Exception e) {
            LOGGER.warn("failed to list sandbox claims for the fleet view", e);
        }
    }

    private void readDirectCrs(SandboxFleet fleet) {
        if (client == null) return;
        try {
            var crs = client.listSandboxes(DIRECT_CR_LABEL_SELECTOR);
            fleet.directCrs = crs.size();
            fleet.oldestCrAgeSeconds = oldestAgeSeconds(crs.stream()
                    .map(cr -> cr.metadata == null ? null : cr.metadata.creationTimestamp).toList());
        } catch (Exception e) {
            LOGGER.warn("failed to list sandbox CRs for the fleet view", e);
        }
    }

    private Long oldestAgeSeconds(List<String> creationTimestamps) {
        Long oldest = null;
        var now = Instant.now();
        for (var timestamp : creationTimestamps) {
            if (timestamp == null) continue;
            try {
                var age = now.getEpochSecond() - Instant.parse(timestamp).getEpochSecond();
                if (oldest == null || age > oldest) oldest = age;
            } catch (Exception e) {
                LOGGER.debug("unparseable sandbox creation timestamp: {}", timestamp, e);
            }
        }
        return oldest;
    }
}
