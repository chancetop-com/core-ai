package ai.core.api.server.share;

import ai.core.api.server.trace.ListSpansResponse;
import ai.core.api.server.trace.SpanView;
import ai.core.api.server.trace.TraceView;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * @author Xander
 */
public interface PublicSessionShareWebService {
    @GET
    @Path("/api/public/session-shares/:token")
    SharedSessionView get(@PathParam("token") String token);

    @GET
    @Path("/api/public/session-shares/:token/messages")
    SharedSessionMessagesResponse messages(@PathParam("token") String token, ListSharedMessagesRequest request);

    @GET
    @Path("/api/public/session-shares/:token/traces/:traceId")
    TraceView trace(@PathParam("token") String token, @PathParam("traceId") String traceId);

    @GET
    @Path("/api/public/session-shares/:token/traces/:traceId/spans")
    ListSpansResponse spans(@PathParam("token") String token, @PathParam("traceId") String traceId);

    @GET
    @Path("/api/public/session-shares/:token/traces/:traceId/spans/:spanId")
    SpanView span(@PathParam("token") String token, @PathParam("traceId") String traceId, @PathParam("spanId") String spanId);
}
