package ai.core.server.trace.web.trace;

import com.fasterxml.jackson.databind.ObjectMapper;

import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;

import ai.core.server.trace.service.TraceService;

/**
 * @author Xander
 */
public class TraceController {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Inject
    TraceService traceService;

    public Response list(Request request) {
        var params = request.queryParams();
        int offset = Integer.parseInt(params.getOrDefault("offset", "0"));
        int limit = Integer.parseInt(params.getOrDefault("limit", "20"));
        var traces = traceService.list(offset, limit);
        return jsonResponse(traces);
    }

    public Response get(Request request) {
        String traceId = request.pathParam("traceId");
        var trace = traceService.get(traceId);
        if (trace == null) {
            return Response.text("not found").status(core.framework.api.http.HTTPStatus.NOT_FOUND);
        }
        return jsonResponse(trace);
    }

    public Response spans(Request request) {
        String traceId = request.pathParam("traceId");
        var spans = traceService.spans(traceId);
        return jsonResponse(spans);
    }

    private Response jsonResponse(Object data) {
        try {
            var json = MAPPER.writeValueAsBytes(data);
            return Response.bytes(json).contentType(core.framework.http.ContentType.APPLICATION_JSON);
        } catch (Exception e) {
            return Response.text("serialization error").status(core.framework.api.http.HTTPStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
