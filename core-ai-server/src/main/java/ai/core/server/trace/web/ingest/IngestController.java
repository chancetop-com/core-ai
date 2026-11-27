package ai.core.server.trace.web.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;

import core.framework.api.http.HTTPStatus;
import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.WebContext;

import ai.core.server.rbac.PermissionsBypass;
import ai.core.server.trace.service.MemoryTraceSpanBuffer;
import ai.core.server.web.auth.AuthContext;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * @author Xander
 */
public class IngestController {
    private static final Logger LOGGER = LoggerFactory.getLogger(IngestController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String SOURCE_CLI = "cli";
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{16,128}$");
    private static final int MAX_START_NAME_CHARS = 512;
    private static final int MAX_START_INPUT_BYTES = 4 * 1024 * 1024;
    private static final long MAX_START_PAST_MS = Duration.ofDays(30).toMillis();
    private static final long MAX_START_FUTURE_MS = Duration.ofMinutes(5).toMillis();
    // Client-declared trace origins; extend this table when a new client ships. Anything unlisted (and older
    // clients that send no clientType) keeps the CLI default.
    private static final Map<String, String> SOURCE_BY_CLIENT_TYPE = Map.of("desktop", "desktop");

    static String traceSource(String clientType) {
        return clientType == null ? SOURCE_CLI : SOURCE_BY_CLIENT_TYPE.getOrDefault(clientType, SOURCE_CLI);
    }

    static String truncateUtf8(String value, int maxBytes) {
        if (value == null || value.length() <= maxBytes / 3) return value;
        var raw = value.getBytes(StandardCharsets.UTF_8);
        if (raw.length <= maxBytes) return value;
        int end = maxBytes;
        while (end > 0 && (raw[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(raw, 0, end, StandardCharsets.UTF_8);
    }

    private static String normalizeName(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > MAX_START_NAME_CHARS ? trimmed.substring(0, MAX_START_NAME_CHARS) : trimmed;
    }

    private static Long normalizeStartedAt(Long startedAtEpochMs, String traceId) {
        if (startedAtEpochMs == null) return null;
        long nowMs = System.currentTimeMillis();
        if (startedAtEpochMs < nowMs - MAX_START_PAST_MS || startedAtEpochMs > nowMs + MAX_START_FUTURE_MS) {
            LOGGER.warn("trace start timestamp out of bounds, resetting to server time, traceId={}, startedAtEpochMs={}",
                    traceId, startedAtEpochMs);
            return null;
        }
        return startedAtEpochMs;
    }

    private static IngestStartRequest normalizeStartRequest(IngestStartRequest raw, String traceId) {
        var normalized = new IngestStartRequest();
        normalized.traceId = traceId;
        normalized.name = normalizeName(raw.name);
        normalized.input = truncateUtf8(raw.input, MAX_START_INPUT_BYTES);
        normalized.startedAtEpochMs = normalizeStartedAt(raw.startedAtEpochMs, traceId);
        normalized.serviceName = raw.serviceName;
        normalized.serviceVersion = raw.serviceVersion;
        normalized.environment = raw.environment;
        normalized.clientType = raw.clientType;
        return normalized;
    }

    @Inject
    MemoryTraceSpanBuffer traceBuffer;
    @Inject
    WebContext webContext;

    // Anonymous legacy path: trusts client-supplied user.id, no source stamp.
    @SuppressFBWarnings("REC_CATCH_EXCEPTION")
    public Response ingestSpans(Request request) {
        IngestRequest ingestRequest;
        byte[] body;
        try {
            body = request.body().orElseThrow(() -> new IllegalArgumentException("empty body"));
            ingestRequest = MAPPER.readValue(body, IngestRequest.class);
        } catch (Exception e) {
            return Response.text("bad request: " + e.getMessage()).status(HTTPStatus.BAD_REQUEST);
        }
        if (!traceBuffer.offerIngest(ingestRequest, null, null, body.length)) {
            return Response.text("trace ingest busy").status(HTTPStatus.SERVICE_UNAVAILABLE);
        }
        return Response.text("ok");
    }

    // Authenticated CLI/SDK path: userId resolved from Bearer by AuthInterceptor; source from the client type
    // (cli by default, "desktop" when a desktop app drives the engine).
    @PermissionsBypass
    @SuppressFBWarnings("REC_CATCH_EXCEPTION")
    public Response ingestAuthed(Request request) {
        // Fail loud if this handler is ever reached without authentication (e.g. route whitelisted by mistake);
        // a null userId must never silently become an anonymous, mis-attributed trace.
        var userId = AuthContext.userId(webContext);
        if (userId == null) throw new IllegalStateException("authenticated ingest requires a userId");
        IngestRequest ingestRequest;
        byte[] body;
        try {
            body = request.body().orElseThrow(() -> new IllegalArgumentException("empty body"));
            ingestRequest = MAPPER.readValue(body, IngestRequest.class);
        } catch (Exception e) {
            return Response.text("bad request: " + e.getMessage()).status(HTTPStatus.BAD_REQUEST);
        }
        if (!traceBuffer.offerIngest(ingestRequest, userId, traceSource(ingestRequest.clientType), body.length)) {
            return Response.text("trace ingest busy").status(HTTPStatus.SERVICE_UNAVAILABLE);
        }
        return Response.text("ok");
    }

    @PermissionsBypass
    @SuppressFBWarnings("REC_CATCH_EXCEPTION")
    public Response ingestStartAuthed(Request request) {
        var userId = AuthContext.userId(webContext);
        if (userId == null) throw new IllegalStateException("authenticated ingest requires a userId");
        IngestStartRequest startRequest;
        byte[] body;
        try {
            body = request.body().orElseThrow(() -> new IllegalArgumentException("empty body"));
            startRequest = MAPPER.readValue(body, IngestStartRequest.class);
        } catch (Exception e) {
            return Response.text("bad request: " + e.getMessage()).status(HTTPStatus.BAD_REQUEST);
        }
        String traceId = startRequest == null || startRequest.traceId == null ? null : startRequest.traceId.trim();
        if (traceId == null || !TRACE_ID_PATTERN.matcher(traceId).matches()) {
            return Response.text("invalid").status(HTTPStatus.BAD_REQUEST);
        }
        var normalized = normalizeStartRequest(startRequest, traceId);
        if (!traceBuffer.offerStart(normalized, userId, traceSource(normalized.clientType), body.length)) {
            return Response.text("trace ingest busy").status(HTTPStatus.SERVICE_UNAVAILABLE);
        }
        return Response.text("ok");
    }
}
