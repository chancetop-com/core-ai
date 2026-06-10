package ai.core.server.trace.web.trace;

import com.fasterxml.jackson.databind.ObjectMapper;

import core.framework.api.http.HTTPStatus;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.WebContext;

import ai.core.server.domain.User;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.service.TracePreviewExtractor;
import ai.core.server.trace.service.TraceService;
import ai.core.server.web.auth.AuthContext;
import ai.core.utils.JsonUtil;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Xander
 */
public class TraceController {
    private static final ObjectMapper MAPPER = JsonUtil.OBJECT_MAPPER;

    @Inject
    TraceService traceService;
    @Inject
    MongoCollection<User> userCollection;
    @Inject
    WebContext webContext;

    public Response list(Request request) {
        var scope = traceScope();
        if (scope.userId() == null) return unauthorized();
        var params = request.queryParams();
        var filter = parseFilter(params);
        applyScope(filter, scope);
        filter.offset = Math.max(Integer.parseInt(params.getOrDefault("offset", "0")), 0);
        // lower bound matters: limit=0 means "no limit" at the Mongo driver and would bypass the cap
        filter.limit = Math.clamp(Integer.parseInt(params.getOrDefault("limit", "20")), 1, 200);
        var traces = traceService.list(filter);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("traces", toTraceViews(traces));
        response.put("total", countTotal(filter));
        return jsonResponse(response);
    }

    // total is best-effort: an unindexable filter combination (e.g. pure text search on dev with notablescan)
    // must not break the list, so the frontend falls back to prev/next paging on -1
    private long countTotal(TraceService.TraceListFilter filter) {
        try {
            return traceService.count(filter);
        } catch (Exception e) {
            return -1;
        }
    }

    public Response facets(Request request) {
        var scope = traceScope();
        if (scope.userId() == null) return unauthorized();
        var params = request.queryParams();
        String field = params.get("field");
        if (field == null || field.isEmpty()) {
            return Response.text("missing field parameter").status(HTTPStatus.BAD_REQUEST);
        }
        // Reuse list filter so the facet counts reflect the current query context
        var filter = parseFilter(params);
        applyScope(filter, scope);
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
        var scope = traceScope();
        if (scope.userId() == null) return unauthorized();
        String traceId = request.pathParam("traceId");
        var trace = traceService.get(traceId);
        if (!canRead(trace, scope)) {
            return Response.text("not found").status(HTTPStatus.NOT_FOUND);
        }
        return jsonResponse(toTraceView(trace, new HashMap<>()));
    }

    public Response spans(Request request) {
        var scope = traceScope();
        if (scope.userId() == null) return unauthorized();
        String traceId = request.pathParam("traceId");
        var trace = traceService.get(traceId);
        if (!canRead(trace, scope)) {
            return Response.text("not found").status(HTTPStatus.NOT_FOUND);
        }
        var spans = traceService.spans(trace.traceId);
        return jsonResponse(spans);
    }

    public Response generations(Request request) {
        var scope = traceScope();
        if (scope.userId() == null) return unauthorized();
        var params = request.queryParams();
        int offset = Integer.parseInt(params.getOrDefault("offset", "0"));
        int limit = Integer.parseInt(params.getOrDefault("limit", "20"));
        String model = params.get("model");
        var spans = traceService.generations(offset, limit, model, scope.admin() ? null : scope.userId());
        return jsonResponse(spans);
    }

    public Response sessionSummary(Request request) {
        var scope = traceScope();
        if (scope.userId() == null) return unauthorized();
        var sessionId = request.pathParam("sessionId");
        var summary = traceService.sessionSummary(sessionId, scope.admin() ? null : scope.userId());
        if (summary == null) return Response.text("not found").status(HTTPStatus.NOT_FOUND);
        addAccounts(List.of(summary));
        return jsonResponse(summary);
    }

    private ZonedDateTime parseDateTime(String value) {
        if (value == null || value.isEmpty()) return null;
        return ZonedDateTime.parse(value);
    }

    private TraceScope traceScope() {
        var userId = AuthContext.userId(webContext);
        var admin = userId != null && userCollection.get(userId)
            .map(user -> "admin".equals(user.role))
            .orElse(false);
        return new TraceScope(userId, admin);
    }

    private void applyScope(TraceService.TraceListFilter filter, TraceScope scope) {
        if (!scope.admin()) {
            filter.userId = scope.userId();
        }
    }

    private boolean canRead(Trace trace, TraceScope scope) {
        if (trace == null) return false;
        if (scope.admin()) return true;
        return scope.userId() != null && scope.userId().equals(trace.userId);
    }

    private Response unauthorized() {
        return Response.text("unauthorized").status(HTTPStatus.UNAUTHORIZED);
    }

    private List<TraceView> toTraceViews(List<Trace> traces) {
        var accountCache = new HashMap<String, AccountView>();
        return traces.stream().map(trace -> toTraceView(trace, accountCache, true)).toList();
    }

    private TraceView toTraceView(Trace trace, Map<String, AccountView> accountCache) {
        return toTraceView(trace, accountCache, false);
    }

    // summary views replace the full input/output payload with a short preview to keep list responses small
    private TraceView toTraceView(Trace trace, Map<String, AccountView> accountCache, boolean summary) {
        var view = new TraceView();
        view.id = trace.id;
        view.traceId = trace.traceId;
        view.name = trace.name;
        view.model = trace.model;
        view.type = trace.type;
        view.source = trace.source;
        view.agentName = trace.agentName;
        view.agentId = trace.agentId;
        view.sessionId = trace.sessionId;
        view.userId = trace.userId;
        view.status = trace.status;
        view.errorMessage = trace.errorMessage;
        if (summary) {
            view.preview = TracePreviewExtractor.extract(trace.input);
        } else {
            view.input = trace.input;
            view.output = trace.output;
        }
        view.metadata = trace.metadata;
        view.inputTokens = trace.inputTokens;
        view.outputTokens = trace.outputTokens;
        view.totalTokens = trace.totalTokens;
        view.cachedTokens = trace.cachedTokens;
        view.costUsd = trace.costUsd;
        view.durationMs = trace.durationMs;
        view.startedAt = trace.startedAt;
        view.completedAt = trace.completedAt;
        view.createdAt = trace.createdAt;
        view.updatedAt = trace.updatedAt;
        view.account = accountFor(trace.userId, accountCache);
        return view;
    }

    private void addAccounts(List<Map<String, Object>> sessions) {
        var accountCache = new HashMap<String, AccountView>();
        for (var session : sessions) {
            var userId = (String) session.get("user_id");
            session.put("account", accountFor(userId, accountCache));
        }
    }

    private AccountView accountFor(String userId, Map<String, AccountView> accountCache) {
        if (userId == null || userId.isEmpty()) return null;
        return accountCache.computeIfAbsent(userId, id -> {
            var view = new AccountView();
            view.userId = id;
            userCollection.get(id).ifPresent(user -> {
                view.name = user.name;
                view.email = user.email;
                view.role = user.role;
                view.status = user.status;
            });
            return view;
        });
    }

    private Response jsonResponse(Object data) {
        try {
            var json = MAPPER.writeValueAsBytes(data);
            return Response.bytes(json).contentType(core.framework.http.ContentType.APPLICATION_JSON);
        } catch (Exception e) {
            return Response.text("serialization error").status(HTTPStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private record TraceScope(String userId, boolean admin) {
    }

    public static class TraceView extends Trace {
        public AccountView account;
        public String preview;
    }

    public static class AccountView {
        public String userId;
        public String name;
        public String email;
        public String role;
        public String status;
    }
}
