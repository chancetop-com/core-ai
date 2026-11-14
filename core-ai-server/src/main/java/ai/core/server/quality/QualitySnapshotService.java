package ai.core.server.quality;

import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Builds the immutable facts a rule set is evaluated against and fingerprints them for the pre-commit
 * recheck. External dependency state can change after this point; the fingerprint detects it before commit.
 *
 * @author core-ai
 */
public class QualitySnapshotService {
    private static final Logger LOGGER = LoggerFactory.getLogger(QualitySnapshotService.class);

    @Inject
    QualityReferenceResolver referenceResolver;
    @Inject
    EffectiveCapabilityResolver capabilityResolver;

    public PublishQualitySnapshot snapshot(PublishCandidate candidate, int policyRevision) {
        var config = candidate.config();
        List<ResolvedReference> references;
        try {
            references = referenceResolver.resolve(candidate.entity(), config);
        } catch (RuntimeException e) {
            LOGGER.warn("quality reference resolution failed, agent={}: {}", candidate.entity().id, e.getMessage());
            references = List.of(new ResolvedReference("REFERENCE", null, null, null,
                QualityReferenceStatus.ERROR, "REFERENCE_RESOLUTION_ERROR", "/", -1));
        }
        EffectiveCapabilityView capabilities;
        try {
            capabilities = capabilityResolver.resolve(config.tools, config.subAgentIds);
        } catch (RuntimeException e) {
            LOGGER.warn("quality capability projection failed, agent={}: {}", candidate.entity().id, e.getMessage());
            capabilities = new EffectiveCapabilityView(List.of(), java.util.Map.of(), java.util.Map.of());
        }
        var dependency = dependencyFingerprint(references, capabilities);
        var policy = QualityHashes.textHash("policy:" + policyRevision);
        return new PublishQualitySnapshot(candidate, references, capabilities, dependency, policy);
    }

    public String dependencyFingerprint(List<ResolvedReference> references, EffectiveCapabilityView capabilities) {
        var summary = new StringBuilder();
        for (var reference : references) {
            summary.append(reference.type()).append(':').append(reference.id()).append(':')
                .append(reference.status()).append('\n');
        }
        for (var capability : capabilities.capabilities().entrySet()) {
            summary.append(capability.getKey()).append(':').append(capability.getValue()).append('\n');
        }
        return QualityHashes.textHash(summary.toString());
    }
}
