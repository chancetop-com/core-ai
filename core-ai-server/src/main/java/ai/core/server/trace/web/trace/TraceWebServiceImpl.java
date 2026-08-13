package ai.core.server.trace.web.trace;

import ai.core.api.server.trace.GenerationsRequest;
import ai.core.api.server.trace.ListSpansResponse;
import ai.core.api.server.trace.ListTraceFacetsRequest;
import ai.core.api.server.trace.ListTraceFacetsResponse;
import ai.core.api.server.trace.ListTracesRequest;
import ai.core.api.server.trace.ListTracesResponse;
import ai.core.api.server.trace.SessionSummaryView;
import ai.core.api.server.trace.SpanStatusView;
import ai.core.api.server.trace.SpanTypeView;
import ai.core.api.server.trace.SpanView;
import ai.core.api.server.trace.TraceAccountView;
import ai.core.api.server.trace.TraceFacetView;
import ai.core.api.server.trace.TraceStatusView;
import ai.core.api.server.trace.TraceView;
import ai.core.api.server.trace.TraceWebService;
import ai.core.server.domain.User;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.service.TraceListFilter;
import ai.core.server.trace.service.TracePreviewExtractor;
import ai.core.server.trace.service.TraceService;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.workflow.WorkflowRunService;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import core.framework.web.exception.UnauthorizedException;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
@PermissionsRequired(PermissionCodes.TRACE_VIEW)
public class TraceWebServiceImpl implements TraceWebService {
    private static SpanView toSpanView(Span span) {
        var view = new SpanView();
        view.id = span.id;
        view.traceId = span.traceId;
        view.userId = span.userId;
        view.spanId = span.spanId;
        view.parentSpanId = span.parentSpanId;
        view.name = span.name;
        view.type = span.type != null ? SpanTypeView.valueOf(span.type.name()) : null;
        view.model = span.model;
        view.input = span.input;
        view.output = span.output;
        view.inputTokens = span.inputTokens;
        view.outputTokens = span.outputTokens;
        view.cachedTokens = span.cachedTokens;
        view.costUsd = span.costUsd;
        view.costSource = span.costSource;
        view.pricingModelId = span.pricingModelId;
        view.inputPricePer1MTokens = span.inputPricePer1MTokens;
        view.outputPricePer1MTokens = span.outputPricePer1MTokens;
        view.durationMs = span.durationMs;
        view.status = span.status != null ? SpanStatusView.valueOf(span.status.name()) : null;
        view.errorMessage = span.errorMessage;
        view.attributes = span.attributes;
        view.startedAt = span.startedAt;
        view.completedAt = span.completedAt;
        view.createdAt = span.createdAt;
        return view;
    }

    private static Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    @Inject
    TraceService traceService;
    @Inject
    MongoCollection<User> userCollection;
    @Inject
    MongoCollection<WorkflowRun> workflowRunCollection;
    @Inject
    WebContext webContext;

    @Override
    public ListTracesResponse list(ListTracesRequest request) {
        var scope = traceScope();
        if (scope.userId == null) throw new UnauthorizedException("unauthorized");
        var filter = parseFilter(request);
        applyScope(filter, scope);
        filter.offset = Math.max(request.offset == null ? 0 : request.offset, 0);
        // lower bound matters: limit=0 means "no limit" at the Mongo driver and would bypass the cap
        filter.limit = Math.clamp(request.limit == null ? 20 : request.limit, 1, 200);
        var traces = traceService.list(filter);
        var response = new ListTracesResponse();
        response.traces = toTraceViews(traces);
        response.total = countTotal(filter);
        return response;
    }

    // total is best-effort: an unindexable filter combination (e.g. pure text search on dev with notablescan)
    // must not break the list, so the frontend falls back to prev/next paging on -1
    private long countTotal(TraceListFilter filter) {
        try {
            return traceService.count(filter);
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public ListTraceFacetsResponse facets(ListTraceFacetsRequest request) {
        var scope = traceScope();
        if (scope.userId == null) throw new UnauthorizedException("unauthorized");
        if (request.field == null || request.field.isEmpty()) {
            throw new BadRequestException("missing field parameter");
        }
        var filter = parseFilter(request);
        applyScope(filter, scope);
        var rows = traceService.facets(request.field, filter);
        var facets = rows.stream().map(row -> {
            var view = new TraceFacetView();
            view.value = (String) row.get("value");
            view.count = (Integer) row.get("count");
            return view;
        }).toList();
        var response = new ListTraceFacetsResponse();
        response.facets = facets;
        return response;
    }

    @Override
    public ListSpansResponse generations(GenerationsRequest request) {
        var scope = traceScope();
        if (scope.userId == null) throw new UnauthorizedException("unauthorized");
        int offset = request.offset == null ? 0 : request.offset;
        int limit = request.limit == null ? 20 : request.limit;
        var spans = traceService.generations(offset, limit, request.model, scope.admin ? null : scope.userId);
        var response = new ListSpansResponse();
        response.spans = spans.stream().map(TraceWebServiceImpl::toSpanView).toList();
        return response;
    }

    @Override
    public SessionSummaryView sessionSummary(String sessionId) {
        var scope = traceScope();
        if (scope.userId == null) throw new UnauthorizedException("unauthorized");
        var summary = traceService.sessionSummary(sessionId, scope.admin ? null : scope.userId);
        if (summary == null) throw new NotFoundException("session summary not found");
        var view = new SessionSummaryView();
        view.sessionId = (String) summary.get("session_id");
        view.traceCount = toLong(summary.get("trace_count"));
        view.totalTokens = toLong(summary.get("total_tokens"));
        view.totalCachedTokens = toLong(summary.get("total_cached_tokens"));
        view.totalCostUsd = toDouble(summary.get("total_cost_usd"));
        view.totalDurationMs = toLong(summary.get("total_duration_ms"));
        view.errorCount = toLong(summary.get("error_count"));
        view.userId = (String) summary.get("user_id");
        view.lastTraceAt = summary.get("last_trace_at") != null ? summary.get("last_trace_at").toString() : null;
        view.firstTraceAt = summary.get("first_trace_at") != null ? summary.get("first_trace_at").toString() : null;
        view.firstRequest = (String) summary.get("first_request");
        view.account = accountFor(view.userId, new HashMap<>());
        return view;
    }

    @Override
    public TraceView get(String traceId) {
        var scope = traceScope();
        if (scope.userId == null) throw new UnauthorizedException("unauthorized");
        var trace = traceService.get(traceId);
        if (!canRead(trace, scope)) throw new NotFoundException("trace not found");
        return toTraceView(trace, new HashMap<>(), false);
    }

    @Override
    public ListSpansResponse spans(String traceId) {
        var scope = traceScope();
        if (scope.userId == null) throw new UnauthorizedException("unauthorized");
        var trace = traceService.get(traceId);
        if (!canRead(trace, scope)) throw new NotFoundException("trace not found");
        var spans = traceService.spans(trace.traceId);
        var response = new ListSpansResponse();
        response.spans = spans.stream().map(TraceWebServiceImpl::toSpanView).toList();
        return response;
    }

    @Override
    public SpanView span(String traceId, String spanId) {
        var scope = traceScope();
        if (scope.userId == null) throw new UnauthorizedException("unauthorized");
        var trace = traceService.get(traceId);
        if (!canRead(trace, scope)) throw new NotFoundException("trace not found");
        var span = traceService.span(trace.traceId, spanId);
        if (span == null) throw new NotFoundException("span not found");
        return toSpanView(span);
    }

    private TraceListFilter parseFilter(ListTracesRequest params) {
        var filter = new TraceListFilter();
        filter.q = params.q;
        filter.name = params.name;
        filter.type = params.type;
        filter.source = params.source;
        filter.agentName = params.agentName;
        filter.model = params.model;
        filter.status = params.status;
        filter.sessionId = params.sessionId;
        filter.userId = params.userId;
        // range takes precedence over startFrom/startTo when present
        var range = params.range;
        if (range != null && !range.isEmpty()) {
            var from = relativeRangeStart(range);
            if (from != null) filter.startFrom = from;
        } else {
            filter.startFrom = parseDateTime(params.startFrom);
            filter.startTo = parseDateTime(params.startTo);
        }
        return filter;
    }

    private TraceListFilter parseFilter(ListTraceFacetsRequest params) {
        var filter = new TraceListFilter();
        filter.q = params.q;
        filter.name = params.name;
        filter.type = params.type;
        filter.source = params.source;
        filter.agentName = params.agentName;
        filter.model = params.model;
        filter.status = params.status;
        filter.sessionId = params.sessionId;
        filter.userId = params.userId;
        var range = params.range;
        if (range != null && !range.isEmpty()) {
            var from = relativeRangeStart(range);
            if (from != null) filter.startFrom = from;
        } else {
            filter.startFrom = parseDateTime(params.startFrom);
            filter.startTo = parseDateTime(params.startTo);
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

    private ZonedDateTime parseDateTime(String value) {
        if (value == null || value.isEmpty()) return null;
        return ZonedDateTime.parse(value);
    }

    private TraceScope traceScope() {
        var userId = AuthContext.userId(webContext);
        var admin = userId != null && userCollection.get(userId)
            .map(user -> "admin".equals(user.role))
            .orElse(Boolean.FALSE);
        return new TraceScope(userId, admin);
    }

    private void applyScope(TraceListFilter filter, TraceScope scope) {
        if (!scope.admin) {
            filter.userId = scope.userId;
        }
    }

    private boolean canRead(Trace trace, TraceScope scope) {
        if (trace == null) return false;
        if (scope.admin) return true;
        if (scope.userId == null) return false;
        if (scope.userId.equals(trace.userId)) return true;
        if (trace.metadata == null) return false;
        var workflowRunId = trace.metadata.get("workflow_run_id");
        if (workflowRunId == null || workflowRunId.isBlank()) return false;
        return workflowRunCollection.get(workflowRunId)
            .map(run -> WorkflowRunService.canRead(run, scope.userId))
            .orElse(Boolean.FALSE);
    }

    private List<TraceView> toTraceViews(List<Trace> traces) {
        var accountCache = new HashMap<String, TraceAccountView>();
        return traces.stream().map(trace -> toTraceView(trace, accountCache, true)).toList();
    }

    // summary views replace the full input/output payload with a short preview to keep list responses small
    private TraceView toTraceView(Trace trace, Map<String, TraceAccountView> accountCache, boolean summary) {
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
        view.status = trace.status != null ? TraceStatusView.valueOf(trace.status.name()) : null;
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

    private TraceAccountView accountFor(String userId, Map<String, TraceAccountView> accountCache) {
        if (userId == null || userId.isEmpty()) return null;
        return accountCache.computeIfAbsent(userId, id -> {
            var view = new TraceAccountView();
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

    private record TraceScope(String userId, boolean admin) {
    }
}
