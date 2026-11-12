package ai.core.server.speech;

import ai.core.server.gateway.GatewayCallAuditService;
import ai.core.server.gateway.MediaJobOwner;
import ai.core.server.hub.HubCallAuditService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.util.StopWatch;
import core.framework.web.Controller;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/**
 * Multipart speech-to-text endpoint for clients outside an agent session. The audio field may be named
 * {@code audio} (preferred) or anything else when the caller sends a single file; {@code model},
 * {@code language} and {@code prompt} are optional form fields.
 *
 * @author stephen
 */
@PermissionsRequired(PermissionCodes.CHAT_USE)
public class SpeechTranscriptionController implements Controller {
    // core-ng's default maxEntitySize is 10 MB for the whole multipart body; stay under it so the
    // caller gets this clear message instead of a 413 from the transport.
    private static final int MAX_AUDIO_BYTES = 9 * 1024 * 1024;
    private static final String AUDIO_FIELD = "audio";

    @Inject
    SpeechTranscriptionService transcriptionService;
    @Inject
    GatewayCallAuditService gatewayCallAudit;
    @Inject
    WebContext webContext;

    @Override
    public Response execute(Request request) {
        return transcribe(request);
    }

    public Response transcribe(Request request) {
        var file = request.files().get(AUDIO_FIELD);
        if (file == null && !request.files().isEmpty()) file = request.files().values().iterator().next();
        if (file == null) throw new BadRequestException("audio file is required", "BAD_REQUEST");
        var audio = read(file.path);
        if (audio.length == 0) throw new BadRequestException("audio file is empty", "BAD_REQUEST");
        if (audio.length > MAX_AUDIO_BYTES) {
            throw new BadRequestException("audio file is too large: " + audio.length + " bytes (max "
                    + MAX_AUDIO_BYTES + ")", "AUDIO_TOO_LARGE");
        }

        var fields = request.formParams();
        var model = fields.get("model");
        var language = fields.get("language");
        var prompt = fields.get("prompt");
        var owner = new MediaJobOwner(AuthContext.userId(webContext),
                request.header("x-session-id").orElse(null), null);
        var auditId = gatewayCallAudit.begin(HubCallAuditService.KIND_MEDIA, "transcribe_audio", "audio",
                hasText(model) ? model : "default", arguments(model, language, file.contentType, audio.length), owner);
        var watch = new StopWatch();
        try {
            var view = transcriptionService.transcribe(audio, contentType(file.contentType), model, language, prompt);
            gatewayCallAudit.finish(auditId, watch, GatewayCallAuditService.Result.completed(
                    view.text == null ? null : view.text.getBytes(StandardCharsets.UTF_8).length, null, null, null));
            return Response.bean(view);
        } catch (RuntimeException e) {
            gatewayCallAudit.finish(auditId, watch,
                    GatewayCallAuditService.Result.failure(gatewayCallAudit.failureStatus(e), e.getMessage()));
            throw e;
        }
    }

    private byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new BadRequestException("failed to read uploaded audio: " + e.getMessage(), "BAD_REQUEST", e);
        }
    }

    private String contentType(String value) {
        return hasText(value) ? value : "application/octet-stream";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String arguments(String model, String language, String contentType, int audioBytes) {
        var args = new LinkedHashMap<String, Object>();
        args.put("model", model);
        args.put("language", language);
        args.put("content_type", contentType);
        args.put("audio_bytes", audioBytes);
        return JsonUtil.toJson(args);
    }
}
