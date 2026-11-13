package ai.core.server.trace;

import ai.core.server.trace.domain.AnalyticsDailyStats;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceDailyStats;
import ai.core.server.trace.domain.TraceStatus;
import core.framework.db.Database;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards the one bug this shape of SQL keeps producing: a statement whose {@code ?} count does not
 * match the parameters bound to it. Such a statement only fails when that code path is reached, which
 * for the daily rollups means once a day.
 *
 * @author stephen
 */
class PgTraceSqlParamsTest {
    private final Database database = mock(Database.class);

    private static void assertParamCount(String sql, Object[] params) {
        long placeholders = sql.chars().filter(ch -> ch == '?').count();
        assertEquals(placeholders, params.length, "parameter count mismatch, sql=" + sql);
    }

    // varargs arrive either as one array or as separate arguments, depending on the call site
    private static Object[] bound(Object[] invocationArgs, int from) {
        var rest = java.util.Arrays.copyOfRange(invocationArgs, from, invocationArgs.length);
        if (rest.length == 1 && rest[0] instanceof Object[] array) return array;
        return rest;
    }

    private void stubDatabase() {
        when(database.select(anyString(), any(), any())).thenAnswer(invocation -> {
            assertParamCount(invocation.getArgument(0), bound(invocation.getArguments(), 2));
            return List.of();
        });
        when(database.selectOne(anyString(), any(), any())).thenAnswer(invocation -> {
            assertParamCount(invocation.getArgument(0), bound(invocation.getArguments(), 2));
            return Optional.empty();
        });
        when(database.execute(anyString(), any())).thenAnswer(invocation -> {
            assertParamCount(invocation.getArgument(0), bound(invocation.getArguments(), 1));
            return 0;
        });
    }

    private TraceStore traceStore() {
        var store = new PgTraceStore();
        store.database = database;
        store.analytics = new PgTraceAnalytics();
        store.analytics.database = database;
        return store;
    }

    @Test
    void traceStoreStatements() {
        stubDatabase();
        var store = traceStore();

        var query = new TraceQuery();
        query.exactId = "ABC";
        query.source = "chat";
        query.sessionIds = List.of("s1", "s2");
        query.userIds = List.of("u1");
        query.startFrom = ZonedDateTime.parse("2026-11-01T00:00:00Z");
        query.startBefore = ZonedDateTime.parse("2026-11-02T00:00:00Z");
        query.limit = 10;
        query.skip = 5;
        store.list(query);
        store.count(query);
        store.facets(query, TraceFacet.MODEL, 5);
        store.traceIds(query, 10);
        store.countStartedBefore(ZonedDateTime.now());

        var update = new TraceUpdate();
        update.traceId = "t1";
        update.status = TraceStatus.COMPLETED;
        update.setOnlyWhenEmpty = TraceUpdate.EmptyGuard.NAME;
        update.valueWhenEmpty = "real name";
        update.namePlaceholders = List.of("placeholder");
        update.updatedAt = ZonedDateTime.now();
        store.update(update);
        store.backfillIdentity("t1", Map.of(TraceIdentityField.USER_ID, "u1", TraceIdentityField.SESSION_ID, "s1"));

        var trace = new Trace();
        trace.id = "t1";
        trace.traceId = "t1";
        store.insert(trace);
        store.insertIfAbsent(trace);
        store.deleteByIds(List.of("t1", "t2"));
    }

    @Test
    void traceAnalyticsStatements() {
        stubDatabase();
        var store = traceStore();
        var from = ZonedDateTime.parse("2026-11-01T00:00:00Z");
        var to = ZonedDateTime.parse("2026-11-02T00:00:00Z");

        var query = new TraceQuery();
        query.userId = "u1";
        for (var group : TraceAggregation.Group.values()) {
            var aggregation = new TraceAggregation();
            aggregation.query = query;
            aggregation.group = group;
            aggregation.firstField = TraceAggregation.FirstField.TRACE_ID;
            aggregation.latestFirst = true;
            aggregation.limit = 5;
            store.aggregate(aggregation);
        }

        store.dailyRollup(from, to);
        store.analyticsRollup(from, to, Map.of("m1", "p1", "m2", "p2"), Map.of("p1", "Pn1"));
        store.analyticsRollup(from, to, Map.of(), Map.of());

        store.realtimeStats(from, to, null, Map.of("m1", "p1"), Map.of("p1", "Pn1"));
        for (var dimension : TraceDimension.values()) {
            store.realtimeStats(from, to, dimension, Map.of("m1", "p1"), Map.of("p1", "Pn1"));
            store.realtimeDimensionTrend(from, to, dimension, List.of("k1", "k2"), Map.of("m1", "p1"),
                Map.of("p1", "Pn1"));
        }
        store.realtimeTrend(from, to, Map.of(), Map.of());
    }

    @Test
    void analyticsStatsStatements() {
        stubDatabase();
        var store = new PgAnalyticsStatsStore();
        store.database = database;
        var from = ZonedDateTime.parse("2026-11-01T00:00:00Z");
        var to = ZonedDateTime.parse("2026-11-02T00:00:00Z");

        store.stats(from, to, null);
        for (var dimension : TraceDimension.values()) {
            store.stats(from, to, dimension);
            store.dimensionTrend(dimension, from, to, List.of("k1", "k2"));
        }
        store.trend(from, to, true);
        store.upsert(new AnalyticsDailyStats());
    }

    @Test
    void dailyStatsStatements() {
        stubDatabase();
        var store = new PgTraceDailyStatsStore();
        store.database = database;
        store.upsert(new TraceDailyStats());
        store.countByDate(ZonedDateTime.parse("2026-11-01T00:00:00Z"));
        store.dailyUsage("u1", ZonedDateTime.parse("2026-11-01T00:00:00Z"),
            ZonedDateTime.parse("2026-11-02T00:00:00Z"));
    }
}
