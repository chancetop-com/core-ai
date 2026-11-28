package ai.core.server.skill.telemetry;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * No-op telemetry store bound when sys.store.mode is not pg.
 *
 * @author stephen
 */
public class NoopSkillTelemetryStore implements SkillTelemetryStore {
    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public boolean recordBatch(String batchId, ZonedDateTime expiresAt, List<SkillTelemetryDelta> items) {
        return false;
    }

    @Override
    public void recordDeltas(List<SkillTelemetryDelta> items) {
    }

    @Override
    public List<SkillRuntimeStatRecord> getStats(String skillId) {
        return List.of();
    }

    @Override
    public List<SkillPapercutRecord> listPapercuts(String skillId, SkillPapercutStatus status) {
        return List.of();
    }

    @Override
    public Optional<SkillPapercutRecord> getPapercut(String skillId, String papercutId) {
        return Optional.empty();
    }

    @Override
    public SkillPapercutRecord upsertPapercut(SkillPapercutInput input) {
        return null;
    }

    @Override
    public Optional<SkillPapercutRecord> updatePapercutStatus(String skillId, String papercutId,
                                                              SkillPapercutStatus status, String actorUserId) {
        return Optional.empty();
    }

    @Override
    public int purgeExpiredBatches(ZonedDateTime now) {
        return 0;
    }

    @Override
    public int purgeClosedPapercutsBefore(ZonedDateTime cutoff) {
        return 0;
    }
}
