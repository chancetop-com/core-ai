package ai.core.server.hub;

import ai.core.server.domain.HubCall;
import core.framework.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.util.HexFormat;

/**
 * Shared audit writer for every Hub execution path ({@code hub_calls}). MCP tool calls, Service API
 * operation calls, agent runs, sandbox tool calls, dataset operations and direct model-gateway calls
 * (media generation and decisions) all record into the same collection with a {@code kind} discriminator.
 * <p>
 * A call is inserted by {@link #begin} before execution and completed by {@link #finish} afterwards.
 * Rows without a user id (auth disabled in dev) are skipped entirely.
 *
 * @author stephen
 */
public class HubCallAuditService {
    public static final String KIND_MCP_TOOL = "mcp_tool";
    public static final String KIND_API_TOOL = "api_tool";
    public static final String KIND_AGENT = "agent";   // agent runs delegated through the Agent Hub
    public static final String KIND_SANDBOX_TOOL = "sandbox_tool";   // calls a script inside a sandbox makes for its session
    public static final String KIND_DATASET = "dataset";   // dataset ops a local script makes for its session
    public static final String KIND_MEDIA = "media";   // direct image/video gateway calls made by remote clients
    public static final String KIND_DECISION = "decision";   // direct decision gateway calls made by remote clients

    private static final int PREVIEW_MAX_CHARS = 512;

    @Inject
    HubCallStore callStore;

    /**
     * @return the audit row id, or null when the call cannot be attributed (userId missing)
     */
    public String begin(BeginRequest request) {
        if (request.userId() == null) return null;   // auth disabled (dev): nothing to attribute, skip audit
        var audit = new HubCall();
        audit.id = request.callId();
        audit.kind = request.kind();
        audit.userId = request.userId();
        audit.userType = request.userType();
        audit.source = request.source() == null || request.source().isBlank() ? "unknown" : request.source();
        audit.target = request.target();
        audit.refId = request.refId();
        audit.group = request.group();
        audit.name = request.name();
        audit.sandboxId = request.sandboxId();
        audit.toolKind = request.toolKind();
        audit.sessionId = request.sessionId();
        audit.argsHash = sha256(request.argumentsJson());
        audit.argsPreview = truncate(request.argumentsJson(), PREVIEW_MAX_CHARS);
        audit.createdAt = ZonedDateTime.now();
        callStore.insert(audit);
        return audit.id;
    }

    public void finish(String callId, long durationMs, String outputText, boolean success,
                       Integer statusCode, String errorMessage) {
        finishWithOutputBytes(callId, durationMs,
                outputText == null ? null : outputText.getBytes(StandardCharsets.UTF_8).length,
                success, statusCode, errorMessage);
    }

    /**
     * Completion variant for callers that already know the output size (media responses carry binary
     * payloads that must not be materialized as text just to count bytes).
     */
    public void finishWithOutputBytes(String callId, long durationMs, Integer outputBytes, boolean success,
                                      Integer statusCode, String errorMessage) {
        if (callId == null) return;
        callStore.finish(callId, durationMs, outputBytes, success, statusCode, truncate(errorMessage, PREVIEW_MAX_CHARS));
    }

    /**
     * Attaches the agent-run coordinates of a {@code kind=agent} row: the A2A task of this turn,
     * the context that continues the conversation, and the token usage when it was reported.
     */
    public void attachRun(String callId, String taskId, String contextId, Long inputTokens, Long outputTokens) {
        if (callId == null) return;
        if (taskId == null && contextId == null && inputTokens == null && outputTokens == null) return;
        callStore.attachRun(callId, taskId, contextId, inputTokens, outputTokens);
    }

    /**
     * Attaches result coordinates known only after execution: the produced artifact/job id of a media
     * generation and the token usage of a model call.
     */
    public void attachResult(String callId, String refId, Long inputTokens, Long outputTokens) {
        if (callId == null) return;
        if (refId == null && inputTokens == null && outputTokens == null) return;
        callStore.attachResult(callId, refId, inputTokens, outputTokens);
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

    /**
     * Everything known about a hub call before it executes; kept as one value so the audit row is built in one place.
     * {@code sandboxId}/{@code toolKind}/{@code sessionId} are only set by sandbox hub calls.
     */
    public record BeginRequest(String callId, String kind, String userId, String userType, String source,
                               String target, String refId, String group, String name, String argumentsJson,
                               String sandboxId, String toolKind, String sessionId) {
    }
}

