package ai.core.server.skill.telemetry;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for papercut deduplication keys and NoopSkillTelemetryStore fallback.
 *
 * @author stephen
 */
class SkillTelemetryStoreUnitTest {
    @Test
    void dedupNormalizesCasePunctuationAndWhitespace() {
        var keyA = SkillPapercutDedup.dedupKey("skill-1", "sha-1", SkillPapercutCategory.BROKEN_COMMAND,
                "Command fails: missing --json flag!");
        var keyB = SkillPapercutDedup.dedupKey("skill-1", "sha-1", SkillPapercutCategory.BROKEN_COMMAND,
                "  command   fails missing json flag ");
        var keyDifferentDigest = SkillPapercutDedup.dedupKey("skill-1", "sha-2", SkillPapercutCategory.BROKEN_COMMAND,
                "Command fails: missing --json flag!");
        assertEquals(keyA, keyB);
        assertNotEquals(keyA, keyDifferentDigest);
    }

    @Test
    void noopStoreReturnsSafeDefaults() {
        var noop = new NoopSkillTelemetryStore();
        assertFalse(noop.isAvailable());
        assertFalse(noop.recordBatch("b1", ZonedDateTime.now(), List.of()));
        assertTrue(noop.getStats("skill-1").isEmpty());
        assertTrue(noop.listPapercuts("skill-1", null).isEmpty());
        assertTrue(noop.getPapercut("skill-1", "p1").isEmpty());
        assertNull(noop.upsertPapercut(new SkillPapercutInput("s", "ns/s", "d",
                SkillPapercutCategory.OTHER, "summary text", null, null, "u", "sess")));
        assertTrue(noop.updatePapercutStatus("s", "p1", SkillPapercutStatus.RESOLVED, "u").isEmpty());
        assertEquals(0, noop.purgeExpiredBatches(ZonedDateTime.now()));
        assertEquals(0, noop.purgeClosedPapercutsBefore(ZonedDateTime.now()));
    }
}
