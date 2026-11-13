package ai.core.server.trace;

import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceFacetRow;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Storage contract for traces. Selected by {@code sys.store.mode}, the same switch the span store uses.
 *
 * @author stephen
 */
public interface TraceStore {
    void insert(Trace trace);

    /**
     * Inserts unless a trace with the same trace id already exists, which is how concurrent ingest
     * requests for one trace stay on a single document.
     *
     * @return true when this call created the trace
     */
    boolean insertIfAbsent(Trace trace);

    Trace findByTraceId(String traceId);

    boolean existsByTraceId(String traceId);

    void update(TraceUpdate update);

    /**
     * Writes all supplied identity columns when at least one of them is still empty. Ingest creates the
     * document from whichever span arrives first, and the root span later supplies the real attribution.
     */
    void backfillIdentity(String traceId, Map<TraceIdentityField, String> values);

    /**
     * @param id      the document id, usually the same value as traceId
     * @param traceId the trace id, used as a fallback because ingested traces do not always use it as _id
     */
    Trace get(String id, String traceId);

    List<Trace> list(TraceQuery query);

    long count(TraceQuery query);

    List<TraceFacetRow> facets(TraceQuery query, TraceFacet facet, int limit);

    List<String> traceIds(TraceQuery query, int limit);

    long countStartedBefore(ZonedDateTime cutoff);

    long deleteByIds(List<String> ids);
}
