package ai.core.server.seoops;

import ai.core.server.seoops.domain.SeoEvidenceState;
import ai.core.server.seoops.domain.SeoEvidenceVerification;
import ai.core.server.seoops.domain.SeoLocation;
import ai.core.server.seoops.domain.SeoLocationReadiness;
import ai.core.server.seoops.domain.SeoTask;
import ai.core.server.seoops.domain.SeoTaskStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author xander
 */
public class SeoTaskPolicy {
    public Readiness evaluate(SeoTask task, SeoLocation location, long revision) {
        List<String> required = task.currentRevision.requiredEvidenceTypes == null
            ? List.of()
            : task.currentRevision.requiredEvidenceTypes;
        Set<String> verified = new HashSet<>();
        Set<String> unverifiable = new HashSet<>();
        if (task.evidenceRefs != null) {
            for (var evidence : task.evidenceRefs) {
                if (evidence.taskRevision == null || evidence.taskRevision != revision) continue;
                if (evidence.verificationStatus == SeoEvidenceVerification.VERIFIED) {
                    verified.add(evidence.requirementKey);
                } else if (evidence.verificationStatus == SeoEvidenceVerification.UNVERIFIABLE) {
                    unverifiable.add(evidence.requirementKey);
                }
            }
        }

        var blockers = new ArrayList<String>();
        var missing = required.stream().filter(type -> !verified.contains(type)).toList();
        var hasUnverifiable = missing.stream().anyMatch(unverifiable::contains);
        SeoEvidenceState evidenceState;
        if (required.isEmpty() || missing.isEmpty()) {
            evidenceState = SeoEvidenceState.VERIFIED;
        } else if (hasUnverifiable) {
            evidenceState = SeoEvidenceState.UNVERIFIABLE;
        } else if (verified.isEmpty()) {
            evidenceState = SeoEvidenceState.NONE;
        } else {
            evidenceState = SeoEvidenceState.PARTIAL;
        }

        if (location != null && location.readinessStatus != SeoLocationReadiness.READY) {
            blockers.add("location readiness is " + location.readinessStatus);
            return new Readiness(evidenceState, SeoTaskStatus.BLOCKED, blockers);
        }
        if (evidenceState == SeoEvidenceState.UNVERIFIABLE) {
            blockers.add("unverifiable evidence: " + String.join(", ", missing));
            return new Readiness(evidenceState, SeoTaskStatus.BLOCKED, blockers);
        }
        if (!missing.isEmpty()) {
            missing.forEach(type -> blockers.add("missing verified evidence: " + type));
            return new Readiness(evidenceState, SeoTaskStatus.NEEDS_INPUT, blockers);
        }
        return new Readiness(evidenceState, SeoTaskStatus.READY_FOR_APPROVAL, blockers);
    }

    public record Readiness(SeoEvidenceState evidenceState, SeoTaskStatus status, List<String> blockers) {
    }
}
