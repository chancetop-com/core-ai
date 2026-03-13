package ai.core.server.trace.web.trace;

import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;

import ai.core.server.trace.service.TraceService;

/**
 * @author Xander
 */
public class TraceController {
    @Inject
    TraceService traceService;

    public Response list(Request request) {
        var params = request.queryParams();
        int offset = Integer.parseInt(params.getOrDefault("offset", "0"));
        int limit = Integer.parseInt(params.getOrDefault("limit", "20"));
        var traces = traceService.list(offset, limit);
        return Response.bean(traces);
    }

    public Response get(Request request) {
        String traceId = request.pathParam("traceId");
        var trace = traceService.get(traceId);
        if (trace == null) {
            return Response.text("not found").status(core.framework.api.http.HTTPStatus.NOT_FOUND);
        }
        return Response.bean(trace);
    }

    public Response spans(Request request) {
        String traceId = request.pathParam("traceId");
        var spans = traceService.spans(traceId);
        return Response.bean(spans);
    }
}
