package ai.core.schedule;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class CronExpressionTest {

    private void assertSameInstant(ZonedDateTime expected, ZonedDateTime actual) {
        assertTrue(expected.isEqual(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void nextAfterDaily() {
        var cron = new CronExpression("0 9 * * *");
        var zone = ZoneId.of("UTC");
        var now = ZonedDateTime.parse("2026-08-21T10:30:00Z");
        assertSameInstant(ZonedDateTime.parse("2026-08-22T09:00:00Z"), cron.nextAfter(now, zone));
    }

    @Test
    void nextAfterEvery30Minutes() {
        var cron = new CronExpression("*/30 * * * *");
        var zone = ZoneId.of("UTC");
        var now = ZonedDateTime.parse("2026-08-21T10:15:00Z");
        assertSameInstant(ZonedDateTime.parse("2026-08-21T10:30:00Z"), cron.nextAfter(now, zone));
    }

    @Test
    void nextAfterWeekday() {
        // 2026-08-21 is a Friday; next weekday 9am is Monday 2026-08-24
        var cron = new CronExpression("0 9 * * 1-5");
        var zone = ZoneId.of("UTC");
        var now = ZonedDateTime.parse("2026-08-21T10:30:00Z");
        assertSameInstant(ZonedDateTime.parse("2026-08-24T09:00:00Z"), cron.nextAfter(now, zone));
    }

    @Test
    void nextAfterRespectsTimezone() {
        var cron = new CronExpression("0 9 * * *");
        var zone = ZoneId.of("Asia/Shanghai");
        var now = ZonedDateTime.parse("2026-08-21T10:30:00Z");
        // 09:00 Asia/Shanghai = 01:00 UTC
        assertSameInstant(ZonedDateTime.parse("2026-08-22T01:00:00Z"), cron.nextAfter(now, zone));
    }

    @Test
    void invalidExpressionRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CronExpression("not-a-cron"));
        assertThrows(IllegalArgumentException.class, () -> new CronExpression("0 9 * *"));
    }
}
