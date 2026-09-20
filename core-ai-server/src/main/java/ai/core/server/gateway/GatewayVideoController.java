package ai.core.server.gateway;

import ai.core.media.domain.MediaReference;
import ai.core.media.domain.Usage;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.media.domain.VideoGenerationResponse;
import ai.core.media.domain.VideoStatusResponse;
import ai.core.media.reference.MediaReferenceParser;
import ai.core.server.rbac.PermissionsBypass;
import ai.core.server.web.auth.AuthContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import core.framework.http.ContentType;
import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenAI-shaped video API backed by provider-specific media adapters. */
@PermissionsBypass
public class GatewayVideoController {
    @Inject
    GatewayMediaProvider gatewayMediaProvider;
    @Inject
    WebContext webContext;

    public Response generate(Request request) {
        return generate(body(request), currentOwner());
    }

    public Response status(Request request) {
        return status(request.pathParam("id"), currentOwner());
    }

    public Response content(Request request) {
        return content(request.pathParam("id"), currentOwner());
    }

    Response generate(byte[] body, MediaJobOwner owner) {
        return json(gatewayMediaProvider.generateVideo(videoRequest(body), owner));
    }

    Response status(String videoId, MediaJobOwner owner) {
        return json(gatewayMediaProvider.getVideoStatus(videoId, owner));
    }

    Response content(String videoId, MediaJobOwner owner) {
        return Response.bytes(gatewayMediaProvider.downloadVideo(videoId, owner))
                .contentType(ContentType.create("video/mp4", StandardCharsets.UTF_8));
    }

    static VideoGenerationRequest videoRequest(byte[] body) {
        Map<String, Object> value;
        try {
            value = GatewayJson.MAPPER.readValue(body, GatewaySupport.MAP_TYPE);
        } catch (Exception e) {
            throw new BadRequestException("invalid JSON body: " + e.getMessage(), "BAD_REQUEST", e);
        }
        var model = string(value, "model");
        var prompt = string(value, "prompt");
        if (!GatewaySupport.hasText(model)) throw new BadRequestException("video model is required");
        if (!GatewaySupport.hasText(prompt)) throw new BadRequestException("video prompt is required");
        return new VideoGenerationRequest(
                model, prompt, integer(value.get("seconds")), string(value, "size"),
                references(first(value, "input_references", "inputReferences")),
                jsonString(first(value, "provider_extra", "providerExtra")),
                string(value, "previous_video_id", "previousVideoId", "previous_interaction_id", "previousInteractionId"));
    }

    private static List<MediaReference> references(Object value) {
        if (value == null) return null;
        try {
            return MediaReferenceParser.parse(jsonString(value), "input_references");
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage(), "BAD_REQUEST", e);
        }
    }

    private static Object first(Map<String, Object> value, String... names) {
        for (var name : names) if (value.containsKey(name)) return value.get(name);
        return null;
    }

    private static String string(Map<String, Object> value, String... names) {
        var result = first(value, names);
        return result instanceof String text ? text : null;
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static String jsonString(Object value) {
        if (value == null) return null;
        if (value instanceof String text) return text;
        try {
            return GatewayJson.MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("invalid JSON value: " + e.getMessage(), "BAD_REQUEST", e);
        }
    }

    private static Response json(VideoGenerationResponse value) {
        var body = new LinkedHashMap<String, Object>();
        body.put("id", value.id());
        body.put("status", value.status());
        body.put("created_at", value.createdAt());
        body.put("usage", usage(value.usage()));
        if (!value.notes().isEmpty()) body.put("notes", value.notes());
        return json(body);
    }

    private static Response json(VideoStatusResponse value) {
        var body = new LinkedHashMap<String, Object>();
        body.put("id", value.id());
        body.put("status", value.status());
        body.put("progress", value.progress());
        body.put("error", value.error());
        body.put("completed_at", value.completedAt());
        body.put("credits_consumed", value.creditsConsumed());
        body.put("upstream_cost_usd", value.upstreamCostUsd());
        body.put("usage", usage(value.usage()));
        return json(body);
    }

    private static Map<String, Object> usage(Usage value) {
        if (value == null) return null;
        var body = new LinkedHashMap<String, Object>();
        body.put("total_tokens", value.totalTokens());
        body.put("image_count", value.imageCount());
        body.put("video_seconds", value.videoSeconds());
        body.put("input_tokens", value.inputTokens());
        body.put("output_tokens", value.outputTokens());
        body.put("input_text_tokens", value.inputTextTokens());
        body.put("input_image_tokens", value.inputImageTokens());
        body.put("upstream_cost_usd", value.upstreamCostUsd());
        body.put("output_video_tokens", value.outputVideoTokens());
        return body;
    }

    private static Response json(Map<String, Object> value) {
        try {
            return Response.bytes(GatewayJson.MAPPER.writeValueAsBytes(value)).contentType(ContentType.APPLICATION_JSON);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("failed to serialize gateway video response", e);
        }
    }

    private MediaJobOwner currentOwner() {
        return new MediaJobOwner(AuthContext.userId(webContext), null, null);
    }

    private byte[] body(Request request) {
        return request.body().orElseThrow(() -> new BadRequestException("body is required"));
    }
}
