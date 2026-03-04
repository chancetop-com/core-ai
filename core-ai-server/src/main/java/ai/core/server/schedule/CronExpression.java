package ai.core.server.schedule;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import java.util.Set;
import java.util.TreeSet;

/**
 * Simple cron expression parser supporting: minute hour dayOfMonth month dayOfWeek
 *
 * @author stephen
 */
public class CronExpression {
    private final Set<Integer> minutes;
    private final Set<Integer> hours;
    private final Set<Integer> daysOfMonth;
    private final Set<Integer> months;
    private final Set<Integer> daysOfWeek;

    public CronExpression(String expression) {
        var parts = expression.trim().split("\\s+");
        if (parts.length != 5) throw new IllegalArgumentException("invalid cron expression: " + expression);
        minutes = parseField(parts[0], 0, 59);
        hours = parseField(parts[1], 0, 23);
        daysOfMonth = parseField(parts[2], 1, 31);
        months = parseField(parts[3], 1, 12);
        daysOfWeek = parseField(parts[4], 0, 6);
    }

    public ZonedDateTime nextAfter(ZonedDateTime after, ZoneId zone) {
        var candidate = after.withZoneSameInstant(zone)
            .plusMinutes(1)
            .withSecond(0)
            .withNano(0);

        for (int i = 0; i < 366 * 24 * 60; i++) {
            if (matches(candidate)) return candidate;
            candidate = candidate.plusMinutes(1);
        }
        throw new IllegalStateException("cannot find next run time for cron expression within a year");
    }

    private boolean matches(ZonedDateTime dt) {
        return minutes.contains(dt.getMinute())
            && hours.contains(dt.getHour())
            && daysOfMonth.contains(dt.getDayOfMonth())
            && months.contains(dt.getMonthValue())
            && daysOfWeek.contains(dt.get(ChronoField.DAY_OF_WEEK) % 7);
    }

    private Set<Integer> parseField(String field, int min, int max) {
        var result = new TreeSet<Integer>();
        for (var part : field.split(",")) {
            if ("*".equals(part)) {
                for (int i = min; i <= max; i++) result.add(i);
            } else if (part.contains("/")) {
                var stepParts = part.split("/");
                int start = "*".equals(stepParts[0]) ? min : Integer.parseInt(stepParts[0]);
                int step = Integer.parseInt(stepParts[1]);
                for (int i = start; i <= max; i += step) result.add(i);
            } else if (part.contains("-")) {
                var rangeParts = part.split("-");
                int from = Integer.parseInt(rangeParts[0]);
                int to = Integer.parseInt(rangeParts[1]);
                for (int i = from; i <= to; i++) result.add(i);
            } else {
                result.add(Integer.parseInt(part));
            }
        }
        return result;
    }
}
