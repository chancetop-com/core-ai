package ai.core.server.seoops;

import ai.core.server.seoops.domain.SeoEvidenceVerification;
import ai.core.server.seoops.domain.SeoTask;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author xander
 */
class SeoOpsViewMapperTest {
    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");
    private final SeoOpsViewMapper mapper = new SeoOpsViewMapper(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void reportFreshnessUsesElapsedDayBoundaries() {
        assertEquals("FRESH", mapper.freshness(atDaysAgo(7)));
        assertEquals("AGING", mapper.freshness(atDaysAgo(8)));
        assertEquals("AGING", mapper.freshness(atDaysAgo(30)));
        assertEquals("STALE", mapper.freshness(atDaysAgo(31)));
    }

    @Test
    void reviewClassificationRequiresVerifiedCurrentRevisionEvidence() {
        var task = task(3L);
        task.evidenceRefs = List.of(
            evidence("BASELINE_MEASUREMENT", 3L, SeoEvidenceVerification.VERIFIED),
            evidence("INTERVENTION_RECORD", 3L, SeoEvidenceVerification.VERIFIED),
            evidence("POST_MEASUREMENT", 3L, SeoEvidenceVerification.VERIFIED));
        assertEquals("CORRELATIONAL", mapper.reviewClassification(task));

        task.evidenceRefs = List.of(
            evidence("BASELINE_MEASUREMENT", 3L, SeoEvidenceVerification.VERIFIED),
            evidence("INTERVENTION_RECORD", 3L, SeoEvidenceVerification.VERIFIED),
            evidence("POST_MEASUREMENT", 3L, SeoEvidenceVerification.VERIFIED),
            evidence("CAUSAL_DESIGN", 3L, SeoEvidenceVerification.VERIFIED),
            evidence("CAUSAL_DESIGN", 2L, SeoEvidenceVerification.VERIFIED));
        assertEquals("CAUSAL_READY", mapper.reviewClassification(task));
    }

    @Test
    void unverifiedOrIncompleteEvidenceCannotClaimCausality() {
        var task = task(1L);
        task.evidenceRefs = List.of(
            evidence("BASELINE_MEASUREMENT", 1L, SeoEvidenceVerification.VERIFIED),
            evidence("INTERVENTION_RECORD", 1L, SeoEvidenceVerification.UNVERIFIED),
            evidence("POST_MEASUREMENT", 1L, SeoEvidenceVerification.VERIFIED),
            evidence("CAUSAL_DESIGN", 1L, SeoEvidenceVerification.VERIFIED));
        assertEquals("FACTUAL", mapper.reviewClassification(task));
    }

    private ZonedDateTime atDaysAgo(long days) {
        return ZonedDateTime.ofInstant(NOW.minusSeconds(days * 86_400), ZoneOffset.UTC);
    }

    private SeoTask task(long revision) {
        var task = new SeoTask();
        task.taskRevision = revision;
        return task;
    }

    private SeoTask.EvidenceRef evidence(String type, long revision, SeoEvidenceVerification verification) {
        var evidence = new SeoTask.EvidenceRef();
        evidence.type = type;
        evidence.taskRevision = revision;
        evidence.verificationStatus = verification;
        return evidence;
    }
}
