package ai.core.server.seoops;

import ai.core.server.seoops.domain.SeoEvidenceState;
import ai.core.server.seoops.domain.SeoEvidenceVerification;
import ai.core.server.seoops.domain.SeoLocation;
import ai.core.server.seoops.domain.SeoLocationReadiness;
import ai.core.server.seoops.domain.SeoTask;
import ai.core.server.seoops.domain.SeoTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SeoTaskPolicyTest {
    private final SeoTaskPolicy policy = new SeoTaskPolicy();

    @Test
    void blockedLocationBlocksTaskEvenWithVerifiedEvidence() {
        var task = taskWithRequirements("SOURCE", "PREVIEW");
        task.evidenceRefs.add(evidence(1L, "SOURCE", SeoEvidenceVerification.VERIFIED));
        task.evidenceRefs.add(evidence(1L, "PREVIEW", SeoEvidenceVerification.VERIFIED));

        var readiness = policy.evaluate(task, location(SeoLocationReadiness.BLOCKED), 1L);

        assertEquals(SeoEvidenceState.VERIFIED, readiness.evidenceState());
        assertEquals(SeoTaskStatus.BLOCKED, readiness.status());
        assertEquals(List.of("location readiness is BLOCKED"), readiness.blockers());
    }

    @Test
    void evidenceStateAdvancesOnlyFromCurrentRevision() {
        var task = taskWithRequirements("SOURCE", "PREVIEW");
        task.evidenceRefs.add(evidence(1L, "SOURCE", SeoEvidenceVerification.VERIFIED));
        task.evidenceRefs.add(evidence(1L, "PREVIEW", SeoEvidenceVerification.VERIFIED));

        var readiness = policy.evaluate(task, location(SeoLocationReadiness.READY), 2L);

        assertEquals(SeoEvidenceState.NONE, readiness.evidenceState());
        assertEquals(SeoTaskStatus.NEEDS_INPUT, readiness.status());
    }

    @Test
    void unverifiableRequirementBlocksUntilAReplacementIsVerified() {
        var task = taskWithRequirements("SOURCE", "PREVIEW");
        task.evidenceRefs.add(evidence(1L, "SOURCE", SeoEvidenceVerification.VERIFIED));
        task.evidenceRefs.add(evidence(1L, "PREVIEW", SeoEvidenceVerification.UNVERIFIABLE));

        var blocked = policy.evaluate(task, location(SeoLocationReadiness.READY), 1L);
        assertEquals(SeoEvidenceState.UNVERIFIABLE, blocked.evidenceState());
        assertEquals(SeoTaskStatus.BLOCKED, blocked.status());

        task.evidenceRefs.add(evidence(1L, "PREVIEW", SeoEvidenceVerification.VERIFIED));
        var recovered = policy.evaluate(task, location(SeoLocationReadiness.READY), 1L);
        assertEquals(SeoEvidenceState.VERIFIED, recovered.evidenceState());
        assertEquals(SeoTaskStatus.READY_FOR_APPROVAL, recovered.status());
    }

    @Test
    void partialVerifiedEvidenceNeedsInput() {
        var task = taskWithRequirements("SOURCE", "PREVIEW");
        task.evidenceRefs.add(evidence(1L, "SOURCE", SeoEvidenceVerification.VERIFIED));

        var readiness = policy.evaluate(task, location(SeoLocationReadiness.READY), 1L);

        assertEquals(SeoEvidenceState.PARTIAL, readiness.evidenceState());
        assertEquals(SeoTaskStatus.NEEDS_INPUT, readiness.status());
        assertEquals(List.of("missing verified evidence: PREVIEW"), readiness.blockers());
    }

    private SeoTask taskWithRequirements(String... requirements) {
        var task = new SeoTask();
        task.evidenceRefs = new ArrayList<>();
        var revision = new SeoTask.TaskRevision();
        revision.revision = 1L;
        revision.requiredEvidenceTypes = List.of(requirements);
        task.currentRevision = revision;
        return task;
    }

    private SeoTask.EvidenceRef evidence(Long revision, String requirement, SeoEvidenceVerification verification) {
        var evidence = new SeoTask.EvidenceRef();
        evidence.taskRevision = revision;
        evidence.requirementKey = requirement;
        evidence.verificationStatus = verification;
        return evidence;
    }

    private SeoLocation location(SeoLocationReadiness readiness) {
        var location = new SeoLocation();
        location.readinessStatus = readiness;
        return location;
    }
}
