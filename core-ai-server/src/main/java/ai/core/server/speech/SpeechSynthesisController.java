package ai.core.server.speech;

import ai.core.api.server.speech.SynthesizeSpeechRequest;
import ai.core.server.gateway.GatewayCallAuditService;
import ai.core.server.gateway.MediaJobOwner;
import ai.core.server.hub.HubCallAuditService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.inject.Inject;
import core.framework.util.StopWatch;
import core.framework.web.Controller;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/**
 * JSON text-to-speech endpoint returning the synthesized audio bytes. Metadata is returned in
 * response headers so callers can name the file without parsing the binary body.
 *
 * @author stephen
 */
@PermissionsRequired(PermissionCodes.CHAT_USE)
public class SpeechSynthesisController implements Controller {
    @Inject
    SpeechSynthesisService synthesisService;
    @Inject
    GatewayCallAuditService gatewayCallAudit;
    @Inject
    WebContext webContext;

    @Override
    public Response execute(Request request) {
        return synthesize(request);
    }

    public Response synthesize(Request request) {
        var body = request.body().orElseThrow(() -> new BadRequestException("request body is required", "BAD_REQUEST"));
        SynthesizeSpeechRequest speech;
        try {
            speech = JsonUtil.fromJson(SynthesizeSpeechRequest.class, new String(body, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new BadRequestException("invalid JSON body: " + e.getMessage(), "BAD_REQUEST", e);
        }
        var owner = new MediaJobOwner(AuthContext.userId(webContext),
                request.header("x-session-id").orElse(null), null);
        var auditId = gatewayCallAudit.begin(HubCallAuditService.KIND_MEDIA, "synthesize_speech", "audio",
                hasText(speech.model) ? speech.model : "default", arguments(speech), owner);
        var watch = new StopWatch();
        try {
            var result = synthesisService.synthesize(speech);
            gatewayCallAudit.finish(auditId, watch, GatewayCallAuditService.Result.completed(
                    result.audio().length, null, null, null));
            var response = Response.bytes(result.audio())
                    .contentType(ContentType.create(result.contentType() == null ? "audio/mpeg" : result.contentType(),
                            StandardCharsets.UTF_8));
            if (result.model() != null) response.header("x-speech-model", result.model());
            if (result.provider() != null) response.header("x-speech-provider", result.provider());
            if (result.voice() != null) response.header("x-speech-voice", result.voice());
            return response;
        } catch (RuntimeException e) {
            gatewayCallAudit.finish(auditId, watch,
                    GatewayCallAuditService.Result.failure(gatewayCallAudit.failureStatus(e), e.getMessage()));
            throw e;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String arguments(SynthesizeSpeechRequest request) {
        var args = new LinkedHashMap<String, Object>();
        args.put("model", request.model);
        args.put("voice", request.voice);
        args.put("language", request.language);
        args.put("format", request.format);
        args.put("characters", request.text == null ? 0 : request.text.length());
        return JsonUtil.toJson(args);
    }
}
