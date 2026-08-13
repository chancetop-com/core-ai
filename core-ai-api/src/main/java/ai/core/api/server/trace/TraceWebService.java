package ai.core.api.server.trace;

import core.framework.api.web.service.GET;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * @author stephen
 */
public interface TraceWebService {
    @GET
    @Path("/api/traces")
    ListTracesResponse list(ListTracesRequest request);

    @GET
    @Path("/api/traces/facets")
    ListTraceFacetsResponse facets(ListTraceFacetsRequest request);

    @GET
    @Path("/api/traces/generations")
    ListSpansResponse generations(GenerationsRequest request);

    @GET
    @Path("/api/traces/sessions/:sessionId/summary")
    SessionSummaryView sessionSummary(@PathParam("sessionId") String sessionId);

    @GET
    @Path("/api/traces/:traceId")
    TraceView get(@PathParam("traceId") String traceId);

    @GET
    @Path("/api/traces/:traceId/spans")
    ListSpansResponse spans(@PathParam("traceId") String traceId);

    @GET
    @Path("/api/traces/:traceId/spans/:spanId")
    SpanView span(@PathParam("traceId") String traceId, @PathParam("spanId") String spanId);
}
