package ai.core.server.report.application;

import ai.core.server.domain.Dataset;
import ai.core.server.report.domain.ReportConstants;
import ai.core.server.report.domain.ReportSpec;
import ai.core.server.report.store.PgReportSourceRangeRow;
import core.framework.db.Database;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;

/**
 * Resolves a report time range. Relative ranges are anchored to the wall clock only for the explicit
 * today/rolling kinds; the "of data" and source kinds anchor to the dataset's own min/max time so a
 * lagging snapshot is not silently shifted.
 *
 * @author core-ai
 */
final class ReportRangeResolver {
    static ReportQueryService.ResolvedRange resolve(Context context) {
        var spec = context.spec();
        var override = context.override();
        var kind = override != null && override.kind() != null ? override.kind() : spec.query.timeRange.kind;
        var days = override != null && override.days() != null ? override.days() : spec.query.timeRange.days;
        var zone = ZoneId.of(spec.query.timezone);
        var local = context.evaluatedAt().withZoneSameInstant(zone);
        var today = local.toLocalDate();
        Range range = switch (kind) {
            case ReportConstants.RANGE_TODAY -> new Range(today.atStartOfDay(zone), context.evaluatedAt());
            case ReportConstants.RANGE_YESTERDAY -> new Range(today.minusDays(1).atStartOfDay(zone), today.atStartOfDay(zone));
            case ReportConstants.RANGE_LAST_N_COMPLETE_DAYS -> lastCompleteDays(days, today, zone);
            case ReportConstants.RANGE_LAST_N_DAYS_OF_DATA -> lastDaysOfData(context, days, zone);
            case ReportConstants.RANGE_SOURCE_RANGE -> sourceRange(context, zone);
            case ReportConstants.RANGE_ROLLING_DAYS -> rollingDays(days, context.evaluatedAt());
            case ReportConstants.RANGE_FIXED -> fixed(context, zone);
            default -> throw new ReportQueryException("INVALID_TIME_RANGE", "unsupported range: " + kind);
        };
        if (!range.from.isBefore(range.to)) throw new ReportQueryException("INVALID_TIME_RANGE", "from must be before to");
        int maxDays = ReportConstants.RANGE_SOURCE_RANGE.equals(kind)
                ? ReportConstants.MAX_SOURCE_RANGE_DAYS : ReportSpecValidator.MAX_QUERY_DAYS;
        if (Duration.between(range.from.toInstant(), range.to.toInstant()).toDays() > maxDays) {
            throw new ReportQueryException("INVALID_TIME_RANGE", "range must not exceed " + maxDays + " days");
        }
        return new ReportQueryService.ResolvedRange(range.from, range.to, context.evaluatedAt());
    }

    private static Range lastCompleteDays(Integer days, LocalDate today, ZoneId zone) {
        requireDays(days);
        return new Range(today.minusDays(days).atStartOfDay(zone), today.atStartOfDay(zone));
    }

    private static Range lastDaysOfData(Context context, Integer days, ZoneId zone) {
        requireDays(days);
        var bounds = sourceBounds(context);
        var to = bounds != null && bounds.maxTime() != null
                ? bounds.maxTime().withZoneSameInstant(zone).toLocalDate().plusDays(1).atStartOfDay(zone)
                : context.evaluatedAt();
        return new Range(to.minusDays(days), to);
    }

    private static Range sourceRange(Context context, ZoneId zone) {
        var bounds = sourceBounds(context);
        if (bounds == null || bounds.minTime() == null || bounds.maxTime() == null) {
            return new Range(context.evaluatedAt().minusDays(1), context.evaluatedAt());
        }
        var to = context.time() != null && context.time().type() == ReportQuerySql.TimeType.DATE
                ? bounds.maxTime().withZoneSameInstant(zone).toLocalDate().plusDays(1).atStartOfDay(zone)
                : bounds.maxTime().plusNanos(1);
        return new Range(bounds.minTime(), to);
    }

    private static Range rollingDays(Integer days, ZonedDateTime evaluatedAt) {
        requireDays(days);
        return new Range(evaluatedAt.minusHours(24L * days), evaluatedAt);
    }

    private static Range fixed(Context context, ZoneId zone) {
        var override = context.override();
        var fromText = override != null && override.from() != null ? override.from() : context.spec().query.timeRange.from;
        var toText = override != null && override.to() != null ? override.to() : context.spec().query.timeRange.to;
        return new Range(parseInstant(fromText, zone), parseInstant(toText, zone));
    }

    private static void requireDays(Integer days) {
        if (days == null || days < 1 || days > ReportSpecValidator.MAX_QUERY_DAYS) {
            throw new ReportQueryException("INVALID_TIME_RANGE", "days must be 1.." + ReportSpecValidator.MAX_QUERY_DAYS);
        }
    }

    private static SourceBounds sourceBounds(Context context) {
        if (context.dataset() == null || context.layout() == null
                || context.time() == null || context.database() == null) return null;
        var params = new ArrayList<Object>();
        String minExpression;
        String maxExpression;
        if (context.time().type() == ReportQuerySql.TimeType.DATE) {
            params.add(context.spec().query.timezone);
            params.add(context.spec().query.timezone);
            minExpression = "(min(" + context.time().expression() + ")::timestamp AT TIME ZONE ?::text)";
            maxExpression = "(max(" + context.time().expression() + ")::timestamp AT TIME ZONE ?::text)";
        } else {
            minExpression = "min(" + context.time().expression() + ")";
            maxExpression = "max(" + context.time().expression() + ")";
        }
        params.add(context.spec().source.datasetId);
        return context.database().selectOne("SELECT " + minExpression + " AS min_time, " + maxExpression + " AS max_time FROM "
                        + context.layout().view() + " WHERE dataset_id = ?", PgReportSourceRangeRow.class, params.toArray())
                .map(row -> new SourceBounds(row.minTime, row.maxTime)).orElse(null);
    }

    private static ZonedDateTime parseInstant(String value, ZoneId zone) {
        if (value == null || value.isBlank()) {
            throw new ReportQueryException("INVALID_TIME_RANGE", "fixed range requires ISO from/to");
        }
        var text = value.trim();
        if (text.length() == 10) {
            try {
                return LocalDate.parse(text).atStartOfDay(zone);
            } catch (DateTimeParseException e) {
                throw new ReportQueryException("INVALID_TIME_RANGE", "fixed range requires ISO date or instant", e);
            }
        }
        try {
            return Instant.parse(text).atZone(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // fall through to offset date-time
        }
        try {
            return OffsetDateTime.parse(text).toInstant().atZone(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new ReportQueryException("INVALID_TIME_RANGE", "fixed range requires ISO date or instant", e);
        }
    }

    private ReportRangeResolver() {
    }

    record Context(ReportSpec spec, Dataset dataset, ZonedDateTime evaluatedAt, ReportQueryService.RangeOverride override,
                   ReportQuerySql.ViewLayout layout, ReportQuerySql.TimeColumn time, Database database) {
    }

    private record Range(ZonedDateTime from, ZonedDateTime to) {
    }

    private record SourceBounds(ZonedDateTime minTime, ZonedDateTime maxTime) {
    }
}
