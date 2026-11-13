package ai.core.server.trace;

import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceFacetRow;

import java.util.List;

/**
 * Storage contract for traces. Selected by {@code sys.store.mode}, the same switch the span store uses.
 *
 * @author stephen
 */
public interface TraceStore {
    void insert(Trace trace);

    /**
     * @param id      the document id, usually the same value as traceId
     * @param traceId the trace id, used as a fallback because ingested traces do not always use it as _id
     */
    Trace get(String id, String traceId);

    List<Trace> list(TraceQuery query);

    long count(TraceQuery query);

    List<TraceFacetRow> facets(TraceQuery query, TraceFacet facet, int limit);

    List<String> traceIds(TraceQuery query, int limit);
}
