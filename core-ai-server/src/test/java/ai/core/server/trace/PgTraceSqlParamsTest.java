package ai.core.server.trace;

import ai.core.server.trace.domain.AnalyticsDailyStats;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceDailyStats;
import ai.core.server.trace.domain.TraceStatus;
import core.framework.db.Database;
import core.framework.db.Transaction;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the one bug this shape of SQL keeps producing: a statement whose {@code ?} count does not
 * match the parameters bound to it. Such a statement only fails when that code path is reached, which
 * for the daily rollups means once a day.
 *
 * @author stephen
 */
class PgTraceSqlParamsTest {
    private static TraceQuery fullQuery() {
        var query = new TraceQuery();
        query.exactId = "ABC";
        query.idPrefix = "abc";
        query.type = "LLM";
        query.source = "chat";
        query.agentName = "a1";
        query.agentId = "a1";
        query.agentIds = List.of("a1", "a2");
        query.model = "m1";
        query.status = "COMPLETED";
        query.agentIdPresent = true;
        query.sessionId = "s1";
        query.sessionIds = List.of("s1", "s2");
        query.traceIds = List.of("t1");
        query.anySessionIds = List.of("s1");
        query.anyTraceIds = List.of("t1");
        query.userId = "u1";
        query.extraUserIds = List.of("u2");
        query.createdFrom = ZonedDateTime.parse("2026-11-01T00:00:00Z");
        query.createdTo = ZonedDateTime.parse("2026-11-02T00:00:00Z");
        query.startFrom = ZonedDateTime.parse("2026-11-01T00:00:00Z");
        query.startAfter = ZonedDateTime.parse("2026-11-01T00:00:00Z");
        query.startTo = ZonedDateTime.parse("2026-11-02T00:00:00Z");
        query.startBefore = ZonedDateTime.parse("2026-11-02T00:00:00Z");
        query.limit = 10;
        query.skip = 5;
        return query;
    }

    private static TraceUpdate fullUpdate() {
        var update = new TraceUpdate();
        update.traceId = "t1";
        update.status = TraceStatus.COMPLETED;
        update.errorMessage = "boom";
        update.clearErrorMessage = false;
        update.input = "in";
        update.output = "out";
        update.durationMs = 10L;
        update.completedAt = ZonedDateTime.now();
        update.name = "n";
        update.sessionId = "s";
        update.userId = "u";
        update.source = "chat";
        update.type = "LLM";
        update.agentName = "a";
        update.agentId = "a";
        update.model = "m";
        update.metadata = Map.of("k", "v");
        update.inputTokensDelta = 1L;
        update.outputTokensDelta = 1L;
        update.totalTokensDelta = 1L;
        update.cachedTokensDelta = 1L;
        update.costUsdDelta = 0.1;
        update.updatedAt = ZonedDateTime.now();
        update.requireStatus = TraceStatus.RUNNING;
        return update;
    }

    private static PgTraceStore store(Database database) {
        var store = new PgTraceStore();
        store.database = database;
        store.analytics = new PgTraceAnalytics();
        store.analytics.database = database;
        return store;
    }

    @Test
    void traceQueryStatements() {
        var database = new RecordingDatabase();
        var store = store(database);

        var query = fullQuery();
        store.list(query);
        store.count(query);
        store.facets(query, TraceFacet.MODEL, 5);
        store.traceIds(query, 10);
        store.countStartedBefore(ZonedDateTime.now());

        var scoped = new TraceQuery();
        scoped.userIds = List.of("u1");
        scoped.agentIds = List.of("a1");
        store.count(scoped);

        assertEquals(6, database.statements.size());
    }

    @Test
    void traceWriteStatements() {
        var database = new RecordingDatabase();
        var store = store(database);

        store.update(fullUpdate());

        var guarded = new TraceUpdate();
        guarded.traceId = "t1";
        guarded.setOnlyWhenEmpty = TraceUpdate.EmptyGuard.NAME;
        guarded.valueWhenEmpty = "real name";
        guarded.namePlaceholders = List.of("placeholder");
        store.update(guarded);

        store.backfillIdentity("t1", Map.of(TraceIdentityField.USER_ID, "u1", TraceIdentityField.SESSION_ID, "s1"));

        var trace = new Trace();
        trace.id = "t1";
        trace.traceId = "t1";
        store.insert(trace);
        store.insertIfAbsent(trace);
        store.deleteByIds(List.of("t1", "t2"));
        store.get("t1", "t1");
        store.findByTraceId("t1");
        store.existsByTraceId("t1");

        assertEquals(10, database.statements.size());
    }

    @Test
    void traceAnalyticsStatements() {
        var database = new RecordingDatabase();
        var store = store(database);
        var from = ZonedDateTime.parse("2026-11-01T00:00:00Z");
        var to = ZonedDateTime.parse("2026-11-02T00:00:00Z");

        for (var group : TraceAggregation.Group.values()) {
            var aggregation = new TraceAggregation();
            aggregation.query = fullQuery();
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

        assertEquals(20, database.statements.size());
    }

    @Test
    void analyticsStatsStatements() {
        var database = new RecordingDatabase();
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

        assertEquals(13, database.statements.size());
    }

    @Test
    void dailyStatsStatements() {
        var database = new RecordingDatabase();
        var store = new PgTraceDailyStatsStore();
        store.database = database;
        store.upsert(new TraceDailyStats());
        store.countByDate(ZonedDateTime.parse("2026-11-01T00:00:00Z"));
        store.dailyUsage("u1", ZonedDateTime.parse("2026-11-01T00:00:00Z"),
            ZonedDateTime.parse("2026-11-02T00:00:00Z"));
        assertEquals(3, database.statements.size());
    }

    private static final class RecordingDatabase implements Database {
        private static void verify(String sql, Object[] params) {
            long placeholders = sql.chars().filter(ch -> ch == '?').count();
            assertEquals(placeholders, params.length, "parameter count mismatch, sql=" + sql);
        }

        private final List<String> statements = new ArrayList<>();

        @Override
        public <T> List<T> select(String sql, Class<T> viewClass, Object... params) {
            verify(sql, params);
            statements.add(sql);
            return List.of();
        }

        @Override
        public <T> Optional<T> selectOne(String sql, Class<T> viewClass, Object... params) {
            verify(sql, params);
            statements.add(sql);
            return Optional.empty();
        }

        @Override
        public int execute(String sql, Object... params) {
            verify(sql, params);
            statements.add(sql);
            return 0;
        }

        @Override
        public int[] batchExecute(String sql, List<Object[]> params) {
            for (var row : params) verify(sql, row);
            statements.add(sql);
            var results = new int[params.size()];
            java.util.Arrays.fill(results, 0);
            return results;
        }

        @Override
        public Transaction beginTransaction() {
            throw new UnsupportedOperationException("read/write only in this test");
        }
    }
}
