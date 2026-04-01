package ai.core.server.trace.web.trace;

import com.fasterxml.jackson.databind.ObjectMapper;

import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;

import ai.core.server.trace.service.TraceService;

import java.time.ZonedDateTime;

/**
 * @author Xander
 */
public class TraceController {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Inject
    TraceService traceService;

    public Response list(Request request) {
        var params = request.queryParams();
        var filter = new TraceService.TraceListFilter();
        filter.offset = Integer.parseInt(params.getOrDefault("offset", "0"));
        filter.limit = Integer.parseInt(params.getOrDefault("limit", "20"));
        filter.name = params.get("name");
        filter.status = params.get("status");
        filter.sessionId = params.get("sessionId");
        filter.userId = params.get("userId");
        filter.startFrom = parseDateTime(params.get("startFrom"));
        filter.startTo = parseDateTime(params.get("startTo"));
        var traces = traceService.list(filter);
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

    public Response generations(Request request) {
        var params = request.queryParams();
        int offset = Integer.parseInt(params.getOrDefault("offset", "0"));
        int limit = Integer.parseInt(params.getOrDefault("limit", "20"));
        String model = params.get("model");
        var spans = traceService.generations(offset, limit, model);
        return jsonResponse(spans);
    }

    public Response sessions(Request request) {
        var params = request.queryParams();
        int offset = Integer.parseInt(params.getOrDefault("offset", "0"));
        int limit = Integer.parseInt(params.getOrDefault("limit", "20"));
        var sessions = traceService.sessions(offset, limit);
        return jsonResponse(sessions);
    }

    private ZonedDateTime parseDateTime(String value) {
        if (value == null || value.isEmpty()) return null;
        return ZonedDateTime.parse(value);
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
