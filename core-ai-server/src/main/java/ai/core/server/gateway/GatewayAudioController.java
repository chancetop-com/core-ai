package ai.core.server.gateway;

import ai.core.api.server.speech.SpeechTranscriptSegmentView;
import ai.core.api.server.speech.SpeechTranscriptionView;
import ai.core.api.server.speech.SynthesizeSpeechRequest;
import ai.core.server.hub.HubCallAuditService;
import ai.core.server.rbac.PermissionsBypass;
import ai.core.server.speech.SpeechSynthesisService;
import ai.core.server.speech.SpeechTranscriptionService;
import ai.core.server.web.auth.AuthContext;
import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.inject.Inject;
import core.framework.util.StopWatch;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAI-compatible audio API: {@code POST /audio/transcriptions} and {@code POST /audio/speech}
 * accept the same bodies as OpenAI's endpoints and answer in the same shapes. Model selection and
 * credentials stay in the gateway, so callers only send a model id or omit it to use the server
 * default.
 *
 * @author stephen
 */
@PermissionsBypass
public class GatewayAudioController {
    // core-ng's default maxEntitySize is 10 MB for the whole multipart body; stay under it so the
    // caller gets this clear message instead of a 413 from the transport.
    private static final int MAX_AUDIO_BYTES = 9 * 1024 * 1024;

    @Inject
    SpeechTranscriptionService transcriptionService;
    @Inject
    SpeechSynthesisService synthesisService;
    @Inject
    GatewayCallAuditService gatewayCallAudit;
    @Inject
    WebContext webContext;

    public Response transcribe(Request request) {
        var file = request.files().get("file");
        if (file == null && !request.files().isEmpty()) file = request.files().values().iterator().next();
        if (file == null) throw new BadRequestException("file is required", "BAD_REQUEST");
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
        var format = firstFormText(fields, "response_format", "responseFormat");
        var contentType = GatewaySupport.hasText(file.contentType) ? file.contentType : "application/octet-stream";
        var owner = new MediaJobOwner(AuthContext.userId(webContext), request.header("x-session-id").orElse(null), null);
        var auditId = gatewayCallAudit.begin(HubCallAuditService.KIND_MEDIA, "transcribe_audio", "audio",
                GatewaySupport.hasText(model) ? model : "default", arguments(model, language, contentType, audio.length), owner);
        var watch = new StopWatch();
        try {
            var view = transcriptionService.transcribe(audio, contentType, model, language, prompt);
            gatewayCallAudit.finish(auditId, watch, GatewayCallAuditService.Result.completed(
                    view.text == null ? null : view.text.getBytes(StandardCharsets.UTF_8).length, null, null, null));
            return transcriptionResponse(view, format);
        } catch (RuntimeException e) {
            gatewayCallAudit.finish(auditId, watch,
                    GatewayCallAuditService.Result.failure(gatewayCallAudit.failureStatus(e), e.getMessage()));
            throw e;
        }
    }

    public Response synthesize(Request request) {
        var body = request.body().orElseThrow(() -> new BadRequestException("request body is required", "BAD_REQUEST"));
        Map<String, Object> value;
        try {
            value = GatewayJson.MAPPER.readValue(body, GatewaySupport.MAP_TYPE);
        } catch (Exception e) {
            throw new BadRequestException("invalid JSON body: " + e.getMessage(), "BAD_REQUEST", e);
        }
        var speech = speechRequest(value);
        if (speech.text == null || speech.text.isBlank()) throw new BadRequestException("input is required", "BAD_REQUEST");

        var owner = new MediaJobOwner(AuthContext.userId(webContext), request.header("x-session-id").orElse(null), null);
        var auditId = gatewayCallAudit.begin(HubCallAuditService.KIND_MEDIA, "synthesize_speech", "audio",
                speech.model == null || speech.model.isBlank() ? "default" : speech.model,
                arguments(speech), owner);
        var watch = new StopWatch();
        try {
            var result = synthesisService.synthesize(speech);
            gatewayCallAudit.finish(auditId, watch, GatewayCallAuditService.Result.completed(
                    result.audio().length, null, null, null));
            var response = Response.bytes(result.audio()).contentType(ContentType.parse(
                    result.contentType() == null ? "audio/mpeg" : result.contentType()));
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

    SynthesizeSpeechRequest speechRequest(Map<String, Object> value) {
        var speech = new SynthesizeSpeechRequest();
        speech.text = firstText(value, "input", "text");
        speech.model = firstText(value, "model");
        speech.voice = firstText(value, "voice");
        speech.language = firstText(value, "language");
        speech.format = firstText(value, "response_format", "responseFormat", "format");
        speech.speed = number(value.get("speed"));
        speech.instructions = firstText(value, "instructions");
        return speech;
    }

    private Response transcriptionResponse(SpeechTranscriptionView view, String format) {
        var value = format == null || format.isBlank() ? "json" : format.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "json" -> jsonResponse(Map.of("text", text(view)));
            case "text" -> textResponse(text(view));
            case "verbose_json", "verbose.json" -> jsonResponse(verboseJson(view));
            case "srt" -> textResponse(subtitles(view, true));
            case "vtt" -> textResponse(subtitles(view, false));
            default -> throw new BadRequestException("unsupported response_format: " + format, "BAD_REQUEST");
        };
    }

    Map<String, Object> verboseJson(SpeechTranscriptionView view) {
        var body = new LinkedHashMap<String, Object>();
        body.put("task", "transcribe");
        body.put("language", view.language);
        body.put("duration", duration(view));
        body.put("text", text(view));
        var segments = new ArrayList<Map<String, Object>>();
        var index = 0;
        for (var segment : segments(view)) {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", index++);
            row.put("seek", 0);
            row.put("start", segment.start == null ? Double.valueOf(0) : segment.start);
            row.put("end", segment.end == null ? Double.valueOf(0) : segment.end);
            row.put("text", segment.text == null ? "" : segment.text);
            segments.add(row);
        }
        body.put("segments", segments);
        return body;
    }

    String subtitles(SpeechTranscriptionView view, boolean srt) {
        var builder = new StringBuilder();
        if (!srt) builder.append("WEBVTT\n\n");
        var rows = segments(view);
        if (rows.isEmpty()) {
            var end = duration(view);
            appendCue(builder, srt, 0.0, end == null ? 0.0 : end, text(view));
            return builder.toString();
        }
        var index = 1;
        for (var segment : rows) {
            if (srt) builder.append(index++).append('\n');
            appendCue(builder, srt, segment.start == null ? 0.0 : segment.start,
                    segment.end == null ? 0.0 : segment.end, segment.text == null ? "" : segment.text);
        }
        return builder.toString();
    }

    private void appendCue(StringBuilder builder, boolean srt, double start, double end, String text) {
        builder.append(timestamp(start, srt)).append(" --> ").append(timestamp(end, srt)).append('\n')
                .append(text).append("\n\n");
    }

    private String timestamp(double seconds, boolean srt) {
        long millis = Math.max(0, Math.round(seconds * 1000));
        long hours = millis / 3_600_000;
        long minutes = millis % 3_600_000 / 60_000;
        long secs = millis % 60_000 / 1000;
        long rest = millis % 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d%s%03d", hours, minutes, secs, srt ? "," : ".", rest);
    }

    private List<SpeechTranscriptSegmentView> segments(SpeechTranscriptionView view) {
        return view.segments == null ? List.of() : view.segments;
    }

    private Double duration(SpeechTranscriptionView view) {
        if (view.durationSeconds != null) return view.durationSeconds;
        var rows = segments(view);
        return rows.isEmpty() || rows.getLast().end == null ? null : rows.getLast().end;
    }

    private String text(SpeechTranscriptionView view) {
        return view.text == null ? "" : view.text;
    }

    private Response textResponse(String value) {
        return Response.bytes(value.getBytes(StandardCharsets.UTF_8)).contentType(ContentType.TEXT_PLAIN);
    }

    private Response jsonResponse(Map<String, Object> body) {
        try {
            return Response.bytes(GatewayJson.MAPPER.writeValueAsBytes(body)).contentType(ContentType.APPLICATION_JSON);
        } catch (Exception e) {
            throw new RuntimeException("failed to serialize gateway audio response", e);
        }
    }

    private byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new BadRequestException("failed to read uploaded audio: " + e.getMessage(), "BAD_REQUEST", e);
        }
    }

    private String firstFormText(Map<String, String> value, String... names) {
        for (var name : names) {
            var result = value.get(name);
            if (GatewaySupport.hasText(result)) return result;
        }
        return null;
    }

    private String firstText(Map<String, Object> value, String... names) {
        for (var name : names) {
            var result = value.get(name);
            if (result instanceof String text && GatewaySupport.hasText(text)) return text;
        }
        return null;
    }

    private Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private String arguments(String model, String language, String contentType, int audioBytes) {
        var args = new LinkedHashMap<String, Object>();
        args.put("model", model);
        args.put("language", language);
        args.put("content_type", contentType);
        args.put("audio_bytes", audioBytes);
        return JsonUtil.toJson(args);
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
