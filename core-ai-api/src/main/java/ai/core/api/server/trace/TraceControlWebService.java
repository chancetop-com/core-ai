package ai.core.api.server.trace;

import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * Admin-only control actions on traces, kept separate from the read-only TraceWebService.
 *
 * @author Xander
 */
public interface TraceControlWebService {
    @POST
    @Path("/api/traces/:traceId/stop")
    StopTraceResponse stop(@PathParam("traceId") String traceId);
}
