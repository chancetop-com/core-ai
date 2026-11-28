package ai.core.server.skill.telemetry;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Storage contract for skill runtime telemetry statistics, batch idempotency ledger, and papercuts.
 *
 * @author stephen
 */
public interface SkillTelemetryStore {
    boolean isAvailable();

    boolean recordBatch(String batchId, ZonedDateTime expiresAt, List<SkillTelemetryDelta> items);

    void recordDeltas(List<SkillTelemetryDelta> items);

    List<SkillRuntimeStatRecord> getStats(String skillId);

    List<SkillPapercutRecord> listPapercuts(String skillId, SkillPapercutStatus status);

    Optional<SkillPapercutRecord> getPapercut(String skillId, String papercutId);

    SkillPapercutRecord upsertPapercut(SkillPapercutInput input);

    Optional<SkillPapercutRecord> updatePapercutStatus(String skillId, String papercutId,
                                                       SkillPapercutStatus status, String actorUserId);

    int purgeExpiredBatches(ZonedDateTime now);

    int purgeClosedPapercutsBefore(ZonedDateTime cutoff);
}
