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

    List<TraceAggregateRow> aggregate(TraceAggregation aggregation);

    /**
     * Per user and agent rollup for one UTC day, with averages, P90 and distinct session count.
     */
    List<TraceDailyRollupRow> dailyRollup(ZonedDateTime from, ZonedDateTime to);

    /**
     * Per user, agent, source, model and provider rollup for one UTC day. The provider is derived from
     * the model through the supplied mappings.
     */
    List<TraceDailyRollupRow> analyticsRollup(ZonedDateTime from, ZonedDateTime to,
                                              Map<String, String> modelToProvider,
                                              Map<String, String> providerIdToName);

    /**
     * Realtime rollup over raw traces for the admin analytics screen. A null dimension returns one
     * global row; otherwise one row per dimension value, ordered by total tokens.
     */
    List<TraceStatsRow> realtimeStats(ZonedDateTime from, ZonedDateTime to, TraceDimension dimension,
                                      Map<String, String> modelToProvider,
                                      Map<String, String> providerIdToName);

    List<TraceTrendRow> realtimeTrend(ZonedDateTime from, ZonedDateTime to,
                                      Map<String, String> modelToProvider,
                                      Map<String, String> providerIdToName);

    List<TraceDimensionTrendRow> realtimeDimensionTrend(ZonedDateTime from, ZonedDateTime to,
                                                        TraceDimension dimension, List<String> keys,
                                                        Map<String, String> modelToProvider,
                                                        Map<String, String> providerIdToName);

    List<String> traceIds(TraceQuery query, int limit);

    long countStartedBefore(ZonedDateTime cutoff);

    long deleteByIds(List<String> ids);
}
