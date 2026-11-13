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
                + "parent_span_id text, name text, type text, model text, status text, tool_call_id text, "
                + "input text, output text, input_tokens bigint, output_tokens bigint, cached_tokens bigint, "
                + "cost_usd double precision, cost_source text, pricing_model_id text, "
                + "input_price_per_1m_tokens double precision, output_price_per_1m_tokens double precision, "
                + "duration_ms bigint, error_message text, attributes jsonb, started_at timestamptz, "
                + "completed_at timestamptz, created_at timestamptz)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS spans_span_id_uidx ON app.spans (span_id)");
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
        store.insert(span);

        var loaded = store.find("t1", "l1");
        assertEquals("s1", loaded.id);
        assertEquals(SpanType.LLM, loaded.type);
        assertEquals(SpanStatus.OK, loaded.status);
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
