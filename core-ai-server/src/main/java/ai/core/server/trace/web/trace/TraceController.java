package ai.core.server.trace.web.trace;

import com.fasterxml.jackson.databind.ObjectMapper;

import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;

import ai.core.server.trace.service.TraceService;
import ai.core.utils.JsonUtil;

import java.time.ZonedDateTime;

/**
 * @author Xander
 */
public class TraceController {
    private static final ObjectMapper MAPPER = JsonUtil.OBJECT_MAPPER;

    @Inject
    TraceService traceService;

    public Response list(Request request) {
        var params = request.queryParams();
        var filter = parseFilter(params);
        filter.offset = Integer.parseInt(params.getOrDefault("offset", "0"));
        filter.limit = Integer.parseInt(params.getOrDefault("limit", "20"));
        var traces = traceService.list(filter);
        return jsonResponse(traces);
    }

    public Response facets(Request request) {
        var params = request.queryParams();
        String field = params.get("field");
        if (field == null || field.isEmpty()) {
            return Response.text("missing field parameter").status(core.framework.api.http.HTTPStatus.BAD_REQUEST);
        }
        // Reuse list filter so the facet counts reflect the current query context
        var filter = parseFilter(params);
        var rows = traceService.facets(field, filter);
        return jsonResponse(rows);
    }

    private TraceService.TraceListFilter parseFilter(java.util.Map<String, String> params) {
        var filter = new TraceService.TraceListFilter();
        filter.q = params.get("q");
        filter.name = params.get("name");
        filter.type = params.get("type");
        filter.source = params.get("source");
        filter.agentName = params.get("agentName");
        filter.model = params.get("model");
        filter.status = params.get("status");
        filter.sessionId = params.get("sessionId");
        filter.userId = params.get("userId");
        // range takes precedence over startFrom/startTo when present
        var range = params.get("range");
        if (range != null && !range.isEmpty()) {
            var from = relativeRangeStart(range);
            if (from != null) filter.startFrom = from;
        } else {
            filter.startFrom = parseDateTime(params.get("startFrom"));
            filter.startTo = parseDateTime(params.get("startTo"));
        }
        return filter;
    }

    private ZonedDateTime relativeRangeStart(String range) {
        var now = ZonedDateTime.now();
        return switch (range) {
            case "15m" -> now.minusMinutes(15);
            case "1h" -> now.minusHours(1);
            case "24h" -> now.minusHours(24);
            case "7d" -> now.minusDays(7);
            case "30d" -> now.minusDays(30);
            default -> null;
        };
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
