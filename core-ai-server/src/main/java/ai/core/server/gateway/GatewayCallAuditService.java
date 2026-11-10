package ai.core.server.gateway;

import ai.core.decision.DecisionRequest;
import ai.core.decision.DecisionResponse;
import ai.core.decision.DecisionUsage;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.Usage;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.server.hub.HubCallAuditService;
import ai.core.server.user.UserStore;
import ai.core.server.web.auth.AuthContext;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.util.StopWatch;
import core.framework.web.Request;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import core.framework.web.exception.TooManyRequestsException;
import core.framework.web.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Audits direct model-gateway calls from remote clients (CLI, desktop engine, external apps) into
 * {@code hub_calls}, under the {@code media} and {@code decision} kinds. Only calls that arrive on the
 * gateway HTTP surface are recorded; server-internal media renders and agent turns stay in traces.
 * <p>
 * Auditing is best effort: a failed audit write must never fail a generation or a decision.
 *
 * @author stephen
 */
public class GatewayCallAuditService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayCallAuditService.class);
    private static final String CLIENT_HEADER = "X-Core-AI-Client";

    @Inject
    HubCallAuditService auditService;
    @Inject
    UserStore userStore;
    @Inject
    WebContext webContext;

    /**
     * @return the inserted audit row id, or null when the caller cannot be attributed or the write failed
     */
    public String begin(String kind, String operation, String group, String target, String argumentsJson,
                        MediaJobOwner owner) {
        try {
            var userId = userId(owner);
            if (userId == null) return null;
            var request = request();
            return auditService.begin(new HubCallAuditService.BeginRequest(
                    UUID.randomUUID().toString(), kind, userId, userType(userId),
                    request == null ? null : request.header(CLIENT_HEADER).orElse(null),
                    target, null, group, operation, argumentsJson, null, null, sessionId(owner, request)));
        } catch (RuntimeException e) {
            LOGGER.warn("gateway call audit begin failed, kind={}, operation={}", kind, operation, e);
            return null;
        }
    }

    public void finish(String callId, StopWatch watch, Result result) {
        if (callId == null) return;
        try {
            auditService.finishWithOutputBytes(callId, TimeUnit.NANOSECONDS.toMillis(watch.elapsed()),
                    result.outputBytes(), result.success(), result.statusCode(), result.errorMessage());
            auditService.attachResult(callId, result.refId(),
                    result.usage() == null ? null : result.usage().inputTokens(),
                    result.usage() == null ? null : result.usage().outputTokens());
        } catch (RuntimeException e) {
            LOGGER.warn("gateway call audit finish failed, callId={}", callId, e);
        }
    }

    /** The prompt and generation parameters, with reference images reduced to a count so base64 payloads never reach the audit row. */
    public String imageArguments(ImageGenerationRequest request, boolean edit) {
        var args = new LinkedHashMap<String, Object>();
        args.put("model", request.model());
        args.put("prompt", request.prompt());
        if (request.n() != null) args.put("n", request.n());
        if (request.size() != null) args.put("size", request.size());
        if (request.quality() != null) args.put("quality", request.quality());
        if (request.outputFormat() != null) args.put("output_format", request.outputFormat());
        if (request.background() != null) args.put("background", request.background());
        if (request.inputImages() != null && !request.inputImages().isEmpty()) {
            args.put("input_images", request.inputImages().size());
        }
        if (request.mask() != null) args.put("mask", Boolean.TRUE);
        if (request.previousInteractionId() != null) args.put("previous_interaction_id", request.previousInteractionId());
        if (edit) args.put("edit", Boolean.TRUE);
        return JsonUtil.toJson(args);
    }

    /** The prompt and video parameters, with reference images reduced to a count so base64 payloads never reach the audit row. */
    public String videoArguments(VideoGenerationRequest request) {
        var args = new LinkedHashMap<String, Object>();
        args.put("model", request.model());
        args.put("prompt", request.prompt());
        if (request.seconds() != null) args.put("seconds", request.seconds());
        if (request.size() != null) args.put("size", request.size());
        if (request.inputReferences() != null && !request.inputReferences().isEmpty()) {
            args.put("input_references", request.inputReferences().size());
        }
        if (request.previousInteractionId() != null) args.put("previous_video_id", request.previousInteractionId());
        return JsonUtil.toJson(args);
    }

    /** Question names/types plus the state size; the state content itself is never written to the audit row. */
    public String decisionArguments(DecisionRequest request) {
        var args = new LinkedHashMap<String, Object>();
        args.put("model", request.model());
        args.put("state_bytes", request.state().getBytes(StandardCharsets.UTF_8).length);
        var questions = new LinkedHashMap<String, String>();
        for (var question : request.questions()) {
            questions.put(question.name(), question.type() == null ? null : question.type().value());
        }
        args.put("questions", questions);
        return JsonUtil.toJson(args);
    }

    /** Size of the returned image payloads; base64 text is ASCII, so its char count is its UTF-8 byte count. */
    public Integer imageOutputBytes(ImageGenerationResponse response) {
        if (response.data() == null) return 0;
        long bytes = 0;
        for (var image : response.data()) {
            if (image.b64Json() != null) bytes += image.b64Json().length();
            if (image.url() != null) bytes += image.url().getBytes(StandardCharsets.UTF_8).length;
        }
        return (int) Math.min(Integer.MAX_VALUE, bytes);
    }

    public Integer decisionOutputBytes(DecisionResponse response) {
        return JsonUtil.toJson(response).getBytes(StandardCharsets.UTF_8).length;
    }

    public Long inputTokens(Usage usage) {
        return usage == null || usage.inputTokens() == null ? null : usage.inputTokens().longValue();
    }

    public Long inputTokens(DecisionUsage usage) {
        return usage == null ? null : (long) usage.inputTokens();
    }

    public Long outputTokens(Usage usage) {
        return usage == null || usage.outputTokens() == null ? null : usage.outputTokens().longValue();
    }

    public Long outputTokens(DecisionUsage usage) {
        return usage == null ? null : (long) usage.outputTokens();
    }

    public Integer failureStatus(RuntimeException error) {
        if (error instanceof BadRequestException) return 400;
        if (error instanceof UnauthorizedException) return 401;
        if (error instanceof ForbiddenException) return 403;
        if (error instanceof NotFoundException) return 404;
        if (error instanceof TooManyRequestsException) return 429;
        return 500;
    }

    private String userId(MediaJobOwner owner) {
        if (owner != null && owner.userId() != null && !owner.userId().isBlank()) return owner.userId();
        return webContext == null ? null : AuthContext.userId(webContext);
    }

    private String userType(String userId) {
        if (userStore == null) return null;
        return userStore.get(userId)
                .map(user -> "api".equals(user.userType) ? "api" : "internal")
                .orElse(null);
    }

    private String sessionId(MediaJobOwner owner, Request request) {
        if (owner != null && owner.sessionId() != null && !owner.sessionId().isBlank()) return owner.sessionId();
        return request == null ? null : GatewaySupport.clientSessionId(request);
    }

    private Request request() {
        return webContext == null ? null : webContext.request();
    }

    public record Result(boolean success, Integer statusCode, Integer outputBytes, String errorMessage,
                         String refId, TokenUsage usage) {
        public static Result completed(Integer outputBytes, String refId, Long inputTokens, Long outputTokens) {
            return new Result(true, null, outputBytes, null, refId, new TokenUsage(inputTokens, outputTokens));
        }

        public static Result failure(Integer statusCode, String errorMessage) {
            return new Result(false, statusCode, null, errorMessage, null, null);
        }
    }

    public record TokenUsage(Long inputTokens, Long outputTokens) {
    }
}
