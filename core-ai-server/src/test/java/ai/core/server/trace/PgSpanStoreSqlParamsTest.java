package ai.core.server.trace;

import ai.core.server.testsupport.RecordingDatabase;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.SpanStatus;
import ai.core.server.trace.domain.SpanType;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author stephen
 */
class PgSpanStoreSqlParamsTest {
    private static PgSpanStore store() {
        var store = new PgSpanStore();
        store.database = new RecordingDatabase();
        return store;
    }

    @Test
    void llmMetricQueriesBindEveryPlaceholder() {
        var from = ZonedDateTime.parse("2026-11-23T00:00:00Z");
        var to = ZonedDateTime.parse("2026-11-23T06:00:00Z");
        var store = store();

        store.llmThroughput(from, to, 5);
        store.llmModelMetrics(from, to);
    }

    @Test
    void insertBindsTtftColumn() {
        var span = new Span();
        span.id = "id";
        span.traceId = "trace";
        span.spanId = "span";
        span.type = SpanType.LLM;
        span.status = SpanStatus.OK;
        span.model = "m";
        span.inputTokens = 1L;
        span.outputTokens = 2L;
        span.ttftMs = 300L;
        span.durationMs = 1000L;
        span.attributes = Map.of("k", "v");
        span.startedAt = ZonedDateTime.now();

        store().insert(span);
    }

    @Test
    void existingSpanIdsBindsPlaceholdersAcrossBatches() {
        var db = new RecordingDatabase();
        var store = new PgSpanStore();
        store.database = db;
        var ids = IntStream.range(0, 600).mapToObj(i -> "span-" + i).toList();

        store.existingSpanIds(ids);

        assertEquals(2, db.statementCount());
        assertEquals(List.of(), store.existingSpanIds(List.of()).stream().toList());
    }
}
