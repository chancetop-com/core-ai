package ai.core.server.hub;

import ai.core.server.domain.HubCall;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.bson.conversions.Bson;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HexFormat;

/**
 * Shared audit writer for every Hub execution path ({@code hub_calls}). MCP tool calls,
 * Service API operation calls and (later) agent runs all record into the same collection
 * with a {@code kind} discriminator.
 * <p>
 * A call is inserted by {@link #begin} before execution and completed by {@link #finish}
 * afterwards. Rows without a user id (auth disabled in dev) are skipped entirely.
 *
 * @author stephen
 */
public class HubCallAuditService {
    public static final String KIND_MCP_TOOL = "mcp_tool";
    public static final String KIND_API_TOOL = "api_tool";
    public static final String KIND_AGENT = "agent";   // reserved for later agent-run audit

    private static final int PREVIEW_MAX_CHARS = 512;

    @Inject
    MongoCollection<HubCall> callCollection;

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
        audit.argsHash = sha256(request.argumentsJson());
        audit.argsPreview = truncate(request.argumentsJson(), PREVIEW_MAX_CHARS);
        audit.createdAt = ZonedDateTime.now();
        callCollection.insert(audit);
        return audit.id;
    }

    public void finish(String callId, long durationMs, String outputText, boolean success,
                       Integer statusCode, String errorMessage) {
        if (callId == null) return;
        var sets = new ArrayList<Bson>();
        sets.add(Updates.set("success", success));
        sets.add(Updates.set("is_error", !success));
        sets.add(Updates.set("duration_ms", durationMs));
        sets.add(Updates.set("status_code", statusCode));
        if (outputText != null) {
            sets.add(Updates.set("output_bytes", outputText.getBytes(StandardCharsets.UTF_8).length));
        }
        if (errorMessage != null) {
            sets.add(Updates.set("error_message", truncate(errorMessage, PREVIEW_MAX_CHARS)));
        }
        callCollection.update(Filters.eq("_id", callId), Updates.combine(sets.toArray(new Bson[0])));
    }

    /**
     * Attaches the agent-run coordinates of a {@code kind=agent} row: the A2A task of this turn,
     * the context that continues the conversation, and the token usage when it was reported.
     */
    public void attachRun(String callId, String taskId, String contextId, Long inputTokens, Long outputTokens) {
        if (callId == null) return;
        var sets = new ArrayList<Bson>();
        if (taskId != null) sets.add(Updates.set("task_id", taskId));
        if (contextId != null) sets.add(Updates.set("context_id", contextId));
        if (inputTokens != null) sets.add(Updates.set("input_tokens", inputTokens));
        if (outputTokens != null) sets.add(Updates.set("output_tokens", outputTokens));
        if (sets.isEmpty()) return;
        callCollection.update(Filters.eq("_id", callId), Updates.combine(sets.toArray(new Bson[0])));
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

    /** Everything known about a hub call before it executes; kept as one value so the audit row is built in one place. */
    public record BeginRequest(String callId, String kind, String userId, String userType, String source,
                               String target, String refId, String group, String name, String argumentsJson) {
    }
}
