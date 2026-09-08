package ai.core.server.apitoolhub;

import ai.core.api.server.apitoolhub.ApiToolHubAppMatch;
import ai.core.api.server.apitoolhub.ApiToolHubAppView;
import ai.core.api.server.apitoolhub.ApiToolHubAppsResponse;
import ai.core.api.server.apitoolhub.ApiToolHubLookupResponse;
import ai.core.api.server.apitoolhub.ApiToolHubOperationDetail;
import ai.core.api.server.apitoolhub.ApiToolHubOperationSummary;
import ai.core.api.server.apitoolhub.ApiToolHubSearchResponse;
import ai.core.api.server.mcphub.HubCallRequest;
import ai.core.api.server.mcphub.HubCallResponse;
import ai.core.api.server.mcphub.HubContentPart;
import ai.core.server.apitoolhub.ApiToolCatalogService.AppSummary;
import ai.core.server.apitoolhub.ApiToolCatalogService.CatalogOperation;
import ai.core.server.apitoolhub.ApiToolCatalogService.SearchOutcome;
import ai.core.server.domain.User;
import ai.core.server.hub.HubCallAuditService;
import ai.core.server.tool.CallerContexts;
import ai.core.server.tool.ToolRegistryService;
import ai.core.tool.OutboundCallerContext;
import ai.core.utils.JsonUtil;
import core.framework.http.HTTPResponse;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.util.StopWatch;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Orchestrates API-Tool Hub operations: app listing, catalog search, operation details,
 * function-name lookup and operation execution. Execution goes through
 * {@link ToolRegistryService#callServiceApiOperation(String, String, String, String)} — the
 * same {@code DynamicApiCaller} backend the agent tools use — wrapped in an
 * {@link OutboundCallerContext} scope derived from the authenticated user, so the business
 * backend receives the platform-controlled caller headers. Every call lands in
 * {@code hub_calls} (kind={@code api_tool}).
 * <p>
 * Hub calls are not agent sessions: nothing is written to chat_sessions or traces.
 *
 * @author stephen
 */
public class ApiToolHubService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiToolHubService.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_TIMEOUT_SECONDS = 300;

    private static final ExecutorService CALL_EXECUTOR = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("api-hub-call-", 0).factory()
    );

    @Inject
    ApiToolCatalogService catalog;
    @Inject
    ApiToolHubAccessPolicy accessPolicy;
    @Inject
    HubCallAuditService auditService;
    @Inject
    ToolRegistryService toolRegistryService;
    @Inject
    MongoCollection<User> userCollection;

    public ApiToolHubAppsResponse apps() {
        var response = new ApiToolHubAppsResponse();
        response.apps = catalog.apps().stream().map(this::toAppView).toList();
        return response;
    }

    public ApiToolHubSearchResponse search(String query, String app, String service, Integer limit) {
        SearchOutcome outcome = catalog.search(query, app, service, limit);
        var response = new ApiToolHubSearchResponse();
        response.apps = outcome.apps().stream().map(hit -> {
            var view = new ApiToolHubAppMatch();
            view.name = hit.app();
            view.matchedCount = hit.matchedCount();
            view.score = hit.score();
            return view;
        }).toList();
        response.operations = outcome.operations().stream()
                .map(scored -> toSummary(scored.operation(), scored.score()))
                .toList();
        return response;
    }

    public ApiToolHubOperationDetail describe(String app, String service, String operation) {
        var entry = requireOperation(app, service, operation);
        return toDetail(entry);
    }

    public ApiToolHubLookupResponse lookup(String toolName) {
        var entry = catalog.findByToolName(toolName);
        if (entry == null) throw new NotFoundException("api tool not found: " + toolName);
        var response = new ApiToolHubLookupResponse();
        response.operation = toSummary(entry, 0);
        return response;
    }

    public HubCallResponse call(String userId, String source, String app, String service, String operation,
                                HubCallRequest request) {
        var entry = requireOperation(app, service, operation);
        accessPolicy.checkCanCall(userId, entry.app());

        int timeoutSeconds = normalizeTimeout(request);
        var argumentsJson = normalizeArguments(request);
        String callId = UUID.randomUUID().toString();
        String auditId = auditService.begin(new HubCallAuditService.BeginRequest(callId, HubCallAuditService.KIND_API_TOOL,
                userId, accessPolicy.isApiUser(userId) ? "api" : "internal", source,
                entry.qualifiedName(), entry.refId(), entry.app(), entry.name(), argumentsJson));

        var watch = new StopWatch();
        Future<HTTPResponse> future = CALL_EXECUTOR.submit(() -> {
            var caller = CallerContexts.fromUser(user(userId));
            return OutboundCallerContext.runWith(caller,
                    () -> toolRegistryService.callServiceApiOperation(app, service, operation, argumentsJson));
        });
        try {
            var response = future.get(timeoutSeconds, TimeUnit.SECONDS);
            long durationMs = elapsedMillis(watch);
            int statusCode = response.statusCode;
            var text = response.text();
            boolean failed = statusCode >= 400;
            LOGGER.debug("api hub call completed, target={}, status={}, elapsed={}",
                    entry.qualifiedName(), statusCode, durationMs);
            auditService.finish(auditId, durationMs, text, !failed, statusCode, failed ? "backend rejected with HTTP " + statusCode : null);
            return toResponse(callId, text, failed, statusCode, durationMs);
        } catch (TimeoutException e) {
            future.cancel(true);
            long durationMs = elapsedMillis(watch);
            LOGGER.warn("api hub call timed out, target={}, timeout={}s, elapsed={}",
                    entry.qualifiedName(), timeoutSeconds, durationMs);
            auditService.finish(auditId, durationMs, null, false, null, "timed out after " + timeoutSeconds + "s");
            throw new ApiToolTimeoutException("api tool call timed out after " + timeoutSeconds + "s: "
                    + entry.qualifiedName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            auditService.finish(auditId, elapsedMillis(watch), null, false, null, "interrupted");
            throw new IllegalStateException("api hub call interrupted", e);
        } catch (ExecutionException e) {
            long durationMs = elapsedMillis(watch);
            var cause = e.getCause() != null ? e.getCause() : e;
            // transport failures surface as a failed tool result (HTTP 200 + is_error), not a 5xx
            LOGGER.warn("api hub call failed, target={}, elapsed={}", entry.qualifiedName(), durationMs, cause);
            auditService.finish(auditId, durationMs, null, false, null, cause.getMessage());
            return toResponse(callId, cause.getMessage(), true, null, durationMs);
        } catch (CancellationException e) {
            auditService.finish(auditId, elapsedMillis(watch), null, false, null, "cancelled");
            throw new IllegalStateException("api tool call cancelled: " + entry.qualifiedName(), e);
        }
    }

    private User user(String userId) {
        if (userId == null) return null;
        return userCollection.get(userId).orElse(null);
    }

    /** StopWatch.elapsed() is nanosecond-based; hub responses and audit use milliseconds. */
    private long elapsedMillis(StopWatch watch) {
        return TimeUnit.NANOSECONDS.toMillis(watch.elapsed());
    }

    private CatalogOperation requireOperation(String app, String service, String operation) {
        var entry = catalog.find(app, service, operation);
        if (entry == null) {
            throw new NotFoundException("api tool not found: " + app + "/" + service + "/" + operation);
        }
        return entry;
    }

    private ApiToolHubAppView toAppView(AppSummary app) {
        var view = new ApiToolHubAppView();
        view.name = app.app();
        view.description = app.description();
        view.baseUrl = app.baseUrl();
        view.version = app.version();
        view.serviceCount = app.serviceCount();
        view.operationCount = app.operationCount();
        return view;
    }

    private ApiToolHubOperationSummary toSummary(CatalogOperation operation, int score) {
        var view = new ApiToolHubOperationSummary();
        view.qualifiedName = operation.qualifiedName();
        view.toolName = operation.toolName();
        view.refId = operation.refId();
        view.app = operation.app();
        view.service = operation.service();
        view.name = operation.name();
        view.method = operation.method();
        view.path = operation.path();
        view.description = operation.description();
        view.needAuth = operation.needAuth();
        view.deprecated = operation.deprecated();
        view.score = score;
        return view;
    }

    private ApiToolHubOperationDetail toDetail(CatalogOperation operation) {
        var view = new ApiToolHubOperationDetail();
        view.qualifiedName = operation.qualifiedName();
        view.toolName = operation.toolName();
        view.refId = operation.refId();
        view.app = operation.app();
        view.service = operation.service();
        view.name = operation.name();
        view.method = operation.method();
        view.path = operation.path();
        view.description = operation.description();
        view.needAuth = operation.needAuth();
        view.deprecated = operation.deprecated();
        view.example = operation.example();
        view.requestType = operation.requestType();
        view.responseType = operation.responseType();
        view.inputSchema = operation.inputSchemaJson();
        view.outputSchema = operation.outputSchemaJson();
        return view;
    }

    private HubCallResponse toResponse(String callId, String text, boolean failed, Integer statusCode, long durationMs) {
        var response = new HubCallResponse();
        response.callId = callId;
        response.success = !failed;
        response.isError = failed;
        response.statusCode = statusCode;
        var part = new HubContentPart();
        part.type = "text";
        part.text = text;
        response.content = List.of(part);
        response.text = text;
        response.durationMs = durationMs;
        return response;
    }

    private int normalizeTimeout(HubCallRequest request) {
        if (request == null || request.timeoutSeconds == null) return DEFAULT_TIMEOUT_SECONDS;
        if (request.timeoutSeconds < 1 || request.timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new BadRequestException("timeout_seconds must be between 1 and " + MAX_TIMEOUT_SECONDS);
        }
        return request.timeoutSeconds;
    }

    private String normalizeArguments(HubCallRequest request) {
        var json = request == null || request.arguments == null || request.arguments.isBlank()
                ? "{}" : request.arguments;
        Map<String, Object> parsed;
        try {
            parsed = JsonUtil.toMap(json);
        } catch (RuntimeException e) {
            throw new BadRequestException("arguments must be a valid JSON object: " + e.getMessage(), "BAD_REQUEST", e);
        }
        return JsonUtil.toJson(parsed);
    }
}
