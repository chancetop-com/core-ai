package ai.core.server.trace;

import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.SpanStatus;
import ai.core.server.trace.domain.SpanType;
import core.framework.internal.db.DatabaseImpl;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
@SuppressFBWarnings("REC_CATCH_EXCEPTION")
class PgSpanStoreTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("span-store-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgSpanRow.class);
        database.view(PgTraceCountRow.class);
        database.view(PgTraceIdRow.class);
        database.view(PgLlmThroughputRow.class);
        database.view(PgLlmModelMetricsRow.class);
        createTables();
        return database;
    }

    private static void createTables() {
        try (var connection = DriverManager.getConnection(
                System.getenv("CORE_AI_PG_TEST_URL"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai"));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS app");
            statement.execute("CREATE TABLE IF NOT EXISTS app.spans ("
                + "id text PRIMARY KEY, trace_id text NOT NULL, user_id text, span_id text NOT NULL, "
                + "parent_span_id text, name text, type text, model text, resolved_model text, provider_id text, status text, tool_call_id text, "
                + "input text, output text, input_tokens bigint, output_tokens bigint, cached_tokens bigint, "
                + "reasoning_tokens bigint, "
                + "cost_usd double precision, cost_source text, pricing_model_id text, "
                + "input_price_per_1m_tokens double precision, output_price_per_1m_tokens double precision, "
                + "duration_ms bigint, ttft_ms bigint, first_content_ms bigint, error_message text, attributes jsonb, "
                + "started_at timestamptz, completed_at timestamptz, created_at timestamptz)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS spans_span_id_uidx ON app.spans (span_id)");
            // the shared dev database may hold an older table; the migrations add the columns there
            statement.execute("ALTER TABLE app.spans ADD COLUMN IF NOT EXISTS ttft_ms bigint");
            statement.execute("ALTER TABLE app.spans ADD COLUMN IF NOT EXISTS provider_id text");
            statement.execute("ALTER TABLE app.spans ADD COLUMN IF NOT EXISTS resolved_model text");
            statement.execute("ALTER TABLE app.spans ADD COLUMN IF NOT EXISTS reasoning_tokens bigint");
            statement.execute("ALTER TABLE app.spans ADD COLUMN IF NOT EXISTS first_content_ms bigint");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize span test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    @BeforeEach
    void clearTable() {
        DATABASE.execute("DELETE FROM app.spans");
    }

    @Test
    void roundTripKeepsPayloadAndAttributes() {
        var store = store();
        var span = span("s1", "t1", "l1", SpanType.LLM, ZonedDateTime.now());
        span.input = "prompt";
        span.output = "answer";
        span.providerId = "provider-1";
        store.insert(span);

        var loaded = store.find("t1", "l1");
        assertEquals("s1", loaded.id);
        assertEquals(SpanType.LLM, loaded.type);
        assertEquals(SpanStatus.OK, loaded.status);
        assertEquals("provider-1", loaded.providerId);
        assertEquals("prompt", loaded.input);
        assertEquals("answer", loaded.output);
        assertEquals("v", loaded.attributes.get("k"));
        assertEquals(10L, loaded.inputTokens);
        assertTrue(store.existsToolCall("t1", "tool-1"));
        assertFalse(store.existsToolCall("t1", "tool-x"));
    }

    @Test
    void summaryQueriesSkipPayloadAndRespectOrdering() {
        var store = store();
        var now = ZonedDateTime.now();
        store.insert(span("s1", "t1", "l2", SpanType.LLM, now.plusSeconds(2)));
        store.insert(span("s2", "t1", "l1", SpanType.LLM, now));
        store.insert(span("s3", "t2", "l3", SpanType.LLM, now));

        var traceSpans = store.summariesByTrace("t1", 10);
        assertEquals(List.of("l1", "l2"), traceSpans.stream().map(span -> span.spanId).toList());
        assertNull(traceSpans.getFirst().input);

        assertEquals(3, store.summariesByTraces(List.of("t1", "t2"), 10).size());
        assertEquals(List.of("l1", "l3", "l2"),
            store.llmSpansByTraces(List.of("t1", "t2"), 10).stream().map(span -> span.spanId).toList());
        assertTrue(store.llmSpansByTraces(List.of("missing"), 10).isEmpty());
    }

    @Test
    void generationsFilterByModelAndScope() {
        var store = store();
        var now = ZonedDateTime.now();
        store.insert(span("s1", "t1", "l1", SpanType.LLM, now));
        var second = span("s2", "t1", "l2", SpanType.LLM, now.plusSeconds(1));
        second.model = "m2";
        store.insert(second);
        var third = span("s3", "t2", "l3", SpanType.LLM, now.plusSeconds(2));
        third.model = "m2";
        store.insert(third);
        store.insert(span("s4", "t1", "l4", SpanType.TOOL, now.plusSeconds(3)));

        assertEquals(List.of("l3", "l2", "l1"),
            store.generations(0, 10, null, null, List.of()).stream().map(span -> span.spanId).toList());
        assertEquals(List.of("l1"),
            store.generations(0, 10, "m1", null, List.of()).stream().map(span -> span.spanId).toList());
        assertEquals(List.of("l2", "l1"),
            store.generations(0, 10, null, List.of("user-2"), List.of("t1")).stream()
                .map(span -> span.spanId).toList());
        assertEquals(List.of("l3", "l2"),
            store.generations(0, 2, null, null, List.of()).stream().map(span -> span.spanId).toList());
    }

    @Test
    void bulkInsertIgnoresRowsThatWereAlreadyCopied() {
        var store = store();
        var now = ZonedDateTime.now();
        store.insert(span("s1", "t1", "l1", SpanType.LLM, now));
        store.bulkInsert(List.of(
            span("s1", "t1", "l1", SpanType.LLM, now),
            span("s2", "t1", "l2", SpanType.LLM, now.plusSeconds(1))));

        assertEquals(2, store.pageByTrace("t1", 0, 10).size());
    }

    @Test
    void bulkInsertSplitsSpansWithHugePayloads() {
        var store = store();
        var now = ZonedDateTime.now();
        var huge = "x".repeat(4 * 1024 * 1024);
        var spans = new ArrayList<Span>();
        for (int i = 0; i < 3; i++) {
            var span = span("big-" + i, "t-big", "l-big-" + i, SpanType.TOOL, now.plusSeconds(i));
            span.input = huge;
            span.output = huge;
            spans.add(span);
        }

        store.bulkInsert(spans);

        assertEquals(3, store.pageByTrace("t-big", 0, 10).size());
        assertEquals(huge.length(), store.find("t-big", "l-big-1").input.length());
    }

    @Test
    void archivePagingAndDeletesAreBounded() {
        var store = store();
        var cutoff = ZonedDateTime.now();
        store.insert(span("s1", "t1", "l1", SpanType.LLM, cutoff.minusDays(2)));
        store.insert(span("s2", "t1", "l2", SpanType.LLM, cutoff.minusDays(1)));
        store.insert(span("s3", "t2", "l3", SpanType.LLM, cutoff.plusDays(1)));

        assertEquals(2, store.countBefore(cutoff));
        var page = store.before(cutoff, 0, 1);
        assertEquals(1, page.size());

        assertEquals(2, store.pageByTrace("t1", 0, 10).size());
        assertEquals(2, store.deleteByTraceIds(List.of("t1")));
        assertEquals(0, store.countBefore(cutoff));
        assertEquals(1, store.deleteByIds(List.of("s3")));
        assertNull(store.find("t2", "l3"));
    }

    @Test
    void llmThroughputBucketsCallsAndTokens() {
        var store = store();
        var base = ZonedDateTime.parse("2026-11-23T10:00:00Z");
        var first = span("s1", "t1", "l1", SpanType.LLM, base.plusSeconds(10));
        first.inputTokens = 100L;
        first.outputTokens = 50L;
        first.cachedTokens = 40L;
        first.costUsd = 0.5;
        var second = span("s2", "t1", "l2", SpanType.LLM, base.plusSeconds(70));
        second.inputTokens = 200L;
        second.outputTokens = 60L;
        second.cachedTokens = null;
        second.costUsd = null;
        var failed = span("s3", "t2", "l3", SpanType.LLM, base.plusSeconds(80));
        failed.status = SpanStatus.ERROR;
        failed.inputTokens = 10L;
        failed.outputTokens = 5L;
        store.insert(first);
        store.insert(second);
        store.insert(failed);
        store.insert(span("s4", "t3", "tool-1", SpanType.TOOL, base.plusSeconds(75)));

        var points = store.llmThroughput(base, base.plusMinutes(10), 1);

        assertEquals(2, points.size());
        assertEquals(base.toInstant(), points.getFirst().bucketStart.toInstant());
        assertEquals(1L, points.getFirst().calls);
        assertEquals(100L, points.getFirst().inputTokens);
        assertEquals(50L, points.getFirst().outputTokens);
        assertEquals(40L, points.getFirst().cachedTokens);
        assertEquals(0.5, points.getFirst().costUsd, 1e-9);
        assertEquals(2L, points.get(1).calls);
        assertEquals(1L, points.get(1).errors);
        assertEquals(210L, points.get(1).inputTokens);
        assertEquals(0L, points.get(1).cachedTokens);
    }

    @Test
    void llmModelMetricsAggregatesLatencyTtftAndErrors() {
        var store = store();
        var base = ZonedDateTime.parse("2026-11-23T11:00:00Z");
        insertModelMetricFixtures(store, base);

        var models = store.llmModelMetrics(base, base.plusMinutes(10));

        assertEquals(2, models.size());
        var m1 = models.getFirst();
        assertEquals("m1", m1.model);
        assertEquals(5L, m1.calls);
        assertEquals(1L, m1.errors);
        assertEquals(4L, m1.callsWithTtft);
        assertEquals(400.0, m1.avgTtftMs, 1e-9);
        assertEquals(200.0, m1.p50TtftMs, 1e-9);
        assertEquals(1000.0, m1.p95TtftMs, 1e-9);
        // answer speeds: 100 tokens/1s, (200-50)/3s = 50, (400-380)/2s = 10; weighted (100+150+20)/6s
        assertEquals(45.0, m1.avgOutputTokensPerSecond, 0.01);
        assertEquals(50.0, m1.p50OutputTokensPerSecond, 0.01);
        assertEquals(100.0, m1.p95OutputTokensPerSecond, 0.01);
        assertEquals("m2", models.get(1).model);
        assertEquals(1L, models.get(1).calls);
        assertEquals(0L, models.get(1).callsWithTtft);
        assertNull(models.get(1).p50TtftMs);
        assertNull(models.get(1).p50OutputTokensPerSecond);
    }

    private void insertModelMetricFixtures(PgSpanStore store, ZonedDateTime base) {
        var fast = span("s1", "t1", "l1", SpanType.LLM, base);
        fast.ttftMs = 100L;
        fast.firstContentMs = 100L;
        fast.durationMs = 1100L;
        fast.outputTokens = 100L;
        fast.reasoningTokens = 0L;
        var slow = span("s2", "t2", "l2", SpanType.LLM, base.plusSeconds(1));
        slow.ttftMs = 300L;
        slow.firstContentMs = 300L;
        slow.durationMs = 3300L;
        slow.outputTokens = 200L;
        slow.reasoningTokens = 50L;
        // a reasoning-heavy call: most output tokens were spent before the answer started
        var reasoningHeavy = span("s3", "t3", "l3", SpanType.LLM, base.plusSeconds(2));
        reasoningHeavy.ttftMs = 200L;
        reasoningHeavy.firstContentMs = 500L;
        reasoningHeavy.durationMs = 2500L;
        reasoningHeavy.outputTokens = 400L;
        reasoningHeavy.reasoningTokens = 380L;
        var unmeasured = span("s4", "t4", "l4", SpanType.LLM, base.plusSeconds(3));
        unmeasured.ttftMs = null;
        unmeasured.durationMs = 500L;
        unmeasured.status = SpanStatus.ERROR;
        // the answer arrived as one burst inside 100 ms: a chunk-granularity artifact, not a decode rate
        var bursty = span("s5", "t5", "l5", SpanType.LLM, base.plusSeconds(4));
        bursty.ttftMs = 1000L;
        bursty.firstContentMs = 1000L;
        bursty.durationMs = 1100L;
        bursty.outputTokens = 500L;
        bursty.reasoningTokens = 0L;
        var other = span("s6", "t6", "l6", SpanType.LLM, base.plusSeconds(5));
        other.model = "m2";
        store.insert(fast);
        store.insert(slow);
        store.insert(reasoningHeavy);
        store.insert(unmeasured);
        store.insert(bursty);
        store.insert(other);
    }

    private PgSpanStore store() {
        var store = new PgSpanStore();
        store.database = DATABASE;
        return store;
    }

    private Span span(String id, String traceId, String spanId, SpanType type, ZonedDateTime startedAt) {
        var span = new Span();
        span.id = id;
        span.traceId = traceId;
        span.userId = "user-1";
        span.spanId = spanId;
        span.name = "op";
        span.type = type;
        span.model = "m1";
        span.status = SpanStatus.OK;
        span.toolCallId = "s1".equals(id) ? "tool-1" : null;
        span.input = "p";
        span.output = "o";
        span.inputTokens = 10L;
        span.outputTokens = 20L;
        span.durationMs = 30L;
        span.attributes = Map.of("k", "v");
        span.startedAt = startedAt;
        span.createdAt = startedAt;
        return span;
    }
}
