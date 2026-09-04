package ai.core.server.mcphub;

import ai.core.api.server.mcphub.HubCallRequest;
import ai.core.api.server.mcphub.HubCallResponse;
import ai.core.api.server.mcphub.HubContentPart;
import ai.core.api.server.mcphub.HubServerMatch;
import ai.core.api.server.mcphub.HubServerView;
import ai.core.api.server.mcphub.HubServersResponse;
import ai.core.api.server.mcphub.HubToolDetail;
import ai.core.api.server.mcphub.HubToolSummary;
import ai.core.api.server.mcphub.HubToolsResponse;
import ai.core.server.domain.McpHubCall;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.mcphub.McpToolCatalogService.CatalogTool;
import ai.core.server.tool.ToolRegistryService;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.util.StopWatch;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
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
 * Orchestrates MCP Hub operations: server listing, catalog search, tool details and
 * tool execution. Execution always goes through
 * {@link ToolRegistryService#callMcpServerTool(String, String, String)} — no new MCP
 * connection is ever opened here — and every call is recorded in {@code mcp_hub_calls}.
 * <p>
 * Hub calls are not agent sessions: nothing is written to chat_sessions or traces.
 *
 * @author stephen
 */
public class McpHubService {
    private static final Logger LOGGER = LoggerFactory.getLogger(McpHubService.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_TIMEOUT_SECONDS = 300;
    private static final int PREVIEW_MAX_CHARS = 512;

    private static final ExecutorService CALL_EXECUTOR = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("mcp-hub-call-", 0).factory()
    );

    @Inject
    McpToolCatalogService catalog;
    @Inject
    McpHubAccessPolicy accessPolicy;
    @Inject
    MongoCollection<McpHubCall> callCollection;
    @Inject
    ToolRegistryService toolRegistryService;

    public HubServersResponse servers() {
        var response = new HubServersResponse();
        response.servers = catalog.listServers().stream().map(data -> {
            var view = new HubServerView();
            view.name = data.entry().name;
            view.description = data.entry().description;
            view.category = data.entry().category;
            view.state = data.state();
            view.toolCount = data.toolCount();
            view.stale = data.stale();
            return view;
        }).toList();
        return response;
    }

    public HubToolsResponse search(String query, String serverFilter, Integer limit) {
        var outcome = catalog.search(query, serverFilter, limit);
        var response = new HubToolsResponse();
        response.servers = outcome.servers().stream().map(hit -> {
            var view = new HubServerMatch();
            view.name = hit.name();
            view.matchedCount = hit.matchedCount();
            view.score = hit.serverScore();
            view.state = hit.state();
            view.stale = hit.stale();
            return view;
        }).toList();
        response.tools = outcome.tools().stream().map(scored -> {
            var view = new HubToolSummary();
            var tool = scored.tool();
            view.qualifiedName = tool.qualifiedName();
            view.refId = tool.refId();
            view.server = tool.serverName();
            view.name = tool.name();
            view.description = tool.description();
            view.score = scored.score();
            view.stale = tool.stale();
            return view;
        }).toList();
        return response;
    }

    public HubToolDetail describe(String serverName, String toolName) {
        var entry = catalog.entryByName(serverName);
        if (entry == null) throw new NotFoundException("mcp server not found: " + serverName);
        var tool = catalog.findTool(serverName, toolName);
        if (tool == null) throw new NotFoundException("mcp tool not found: " + serverName + "/" + toolName);
        var detail = new HubToolDetail();
        detail.qualifiedName = tool.qualifiedName();
        detail.refId = tool.refId();
        detail.server = tool.serverName();
        detail.name = tool.name();
        detail.description = tool.description();
        detail.inputSchema = tool.inputSchemaJson();
        detail.serverState = toolRegistryService.getMcpServerState(entry.id).name();
        return detail;
    }

    public HubCallResponse call(String userId, String source, String serverName, String toolName,
                                HubCallRequest request) {
        var entry = catalog.entryByName(serverName);
        if (entry == null) throw new NotFoundException("mcp server not found: " + serverName);
        accessPolicy.checkCanCall(userId, entry);
        var tool = catalog.findTool(serverName, toolName);
        if (tool == null) throw new NotFoundException("mcp tool not found: " + serverName + "/" + toolName);

        int timeoutSeconds = normalizeTimeout(request);
        var argumentsJson = normalizeArguments(request);
        String callId = UUID.randomUUID().toString();
        beginAudit(userId, source, entry, tool, argumentsJson, callId);

        var watch = new StopWatch();
        Future<ToolCallResult> future = CALL_EXECUTOR.submit(
                () -> toolRegistryService.callMcpServerTool(entry.id, tool.name(), argumentsJson));
        try {
            var result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            long durationMs = elapsedMillis(watch);
            boolean failed = result.getStatus() != ToolCallResult.Status.COMPLETED;
            LOGGER.debug("mcp hub call completed, server={}, tool={}, status={}, elapsed={}",
                    entry.name, tool.name(), result.getStatus(), durationMs);
            var state = toolRegistryService.getMcpServerState(entry.id).name();
            var text = result.toResultForLLM();
            finishAudit(callId, durationMs, text, !failed, failed ? "tool failed: " + text : null);
            return toResponse(callId, text, failed, durationMs, state);
        } catch (TimeoutException e) {
            future.cancel(true);
            long durationMs = elapsedMillis(watch);
            LOGGER.warn("mcp hub call timed out, server={}, tool={}, timeout={}s, elapsed={}",
                    entry.name, tool.name(), timeoutSeconds, durationMs);
            finishAudit(callId, durationMs, null, false, "timed out after " + timeoutSeconds + "s");
            throw new McpToolTimeoutException("mcp tool call timed out after " + timeoutSeconds + "s: "
                    + serverName + "/" + toolName, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            finishAudit(callId, elapsedMillis(watch), null, false, "interrupted");
            throw new IllegalStateException("mcp hub call interrupted", e);
        } catch (ExecutionException e) {
            future.cancel(true);
            long durationMs = elapsedMillis(watch);
            var cause = e.getCause() != null ? e.getCause() : e;
            LOGGER.warn("mcp hub call failed, server={}, tool={}, elapsed={}", entry.name, tool.name(), durationMs, cause);
            var state = toolRegistryService.getMcpServerState(entry.id).name();
            finishAudit(callId, durationMs, null, false, cause.getMessage());
            throw new McpServerUnavailableException("mcp server unavailable while calling " + serverName + "/" + toolName
                    + " (state=" + state + "): " + cause.getMessage(), e);
        } catch (CancellationException e) {
            finishAudit(callId, elapsedMillis(watch), null, false, "cancelled");
            throw new McpServerUnavailableException("mcp tool call cancelled: " + serverName + "/" + toolName, e);
        }
    }

    /** StopWatch.elapsed() is nanosecond-based; hub responses and audit use milliseconds. */
    private long elapsedMillis(StopWatch watch) {
        return TimeUnit.NANOSECONDS.toMillis(watch.elapsed());
    }

    private HubCallResponse toResponse(String callId, String text, boolean failed, long durationMs, String state) {
        var response = new HubCallResponse();
        response.callId = callId;
        response.success = !failed;
        response.isError = failed;
        var part = new HubContentPart();
        part.type = "text";
        part.text = text;
        response.content = List.of(part);
        response.text = text;
        response.durationMs = durationMs;
        response.serverState = state;
        return response;
    }

    private void beginAudit(String userId, String source, ToolRegistryEntry entry,
                            CatalogTool tool, String argumentsJson, String callId) {
        if (userId == null) return;   // auth disabled (dev): nothing to attribute, skip audit
        var audit = new McpHubCall();
        audit.id = callId;
        audit.userId = userId;
        audit.userType = accessPolicy.isApiUser(userId) ? "api" : "internal";
        audit.source = source == null || source.isBlank() ? "unknown" : source;
        audit.serverId = entry.id;
        audit.serverName = entry.name;
        audit.toolName = tool.name();
        audit.argsHash = sha256(argumentsJson);
        audit.argsPreview = truncate(argumentsJson, PREVIEW_MAX_CHARS);
        audit.createdAt = ZonedDateTime.now();
        callCollection.insert(audit);
    }

    private void finishAudit(String callId, long durationMs, String text, boolean success, String errorMessage) {
        var sets = new ArrayList<Bson>();
        sets.add(Updates.set("success", success));
        sets.add(Updates.set("is_error", !success));
        sets.add(Updates.set("duration_ms", durationMs));
        if (text != null) {
            sets.add(Updates.set("output_bytes", text.getBytes(StandardCharsets.UTF_8).length));
        }
        if (errorMessage != null) {
            sets.add(Updates.set("error_message", truncate(errorMessage, PREVIEW_MAX_CHARS)));
        }
        callCollection.update(Filters.eq("_id", callId), Updates.combine(sets.toArray(new Bson[0])));
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

    private String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String truncate(String value, int maxChars) {
        if (value == null) return null;
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }
}
