package ai.core.server.analytics;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Date range resolution helpers for analytics queries.
 *
 * @author stephen
 */
final class AnalyticsDateUtils {
    private static final ZoneId UTC = ZoneId.of("UTC");

    static DateRange resolveDateRange(String mode, String range, String from, String to) {
        LocalDate today = LocalDate.now(UTC);
        if ("realtime".equals(mode)) {
            return new DateRange(today.atStartOfDay(UTC), today.plusDays(1).atStartOfDay(UTC));
        }
        LocalDate end = today.minusDays(1);
        LocalDate start;
        if (from != null && to != null) {
            start = LocalDate.parse(from);
            end = LocalDate.parse(to);
        } else if ("7d".equals(range)) {
            start = today.minusDays(7);
        } else {
            start = today.minusDays(30);
        }
        if (!end.isBefore(today)) end = today.minusDays(1);
        return new DateRange(start.atStartOfDay(UTC), end.plusDays(1).atStartOfDay(UTC));
    }

    static DateRange computePrevRange(DateRange bounds) {
        long duration = bounds.to().toLocalDate().toEpochDay() - bounds.from().toLocalDate().toEpochDay();
        if (duration <= 0) return null;
        LocalDate prevEnd = bounds.from().toLocalDate().minusDays(1);
        LocalDate prevStart = prevEnd.minusDays(duration - 1);
        return new DateRange(prevStart.atStartOfDay(UTC), prevEnd.plusDays(1).atStartOfDay(UTC));
    }

    static boolean isHourlyRange(DateRange bounds) {
        long days = bounds.to().toLocalDate().toEpochDay() - bounds.from().toLocalDate().toEpochDay();
        return days <= 2;
    }

    record DateRange(ZonedDateTime from, ZonedDateTime to) {
        DateRange(ZonedDateTime from, ZonedDateTime to, LocalDate today) {
            this(from, to);
        }
    }
}
