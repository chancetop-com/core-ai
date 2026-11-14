package ai.core.server.quality;

import java.util.List;

/**
 * @author core-ai
 */
public record PublishQualitySnapshot(PublishCandidate candidate,
                                     List<ResolvedReference> references,
                                     EffectiveCapabilityView capabilities,
                                     String dependencyFingerprint,
                                     String policyFingerprint) {
}
