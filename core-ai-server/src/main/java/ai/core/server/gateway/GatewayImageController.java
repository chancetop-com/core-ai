package ai.core.server.gateway;

import ai.core.media.domain.ImageData;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.MediaReference;
import ai.core.media.domain.Usage;
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

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenAI-shaped image API backed by provider-specific media adapters. */
@PermissionsBypass
public class GatewayImageController {
    @Inject
    GatewayMediaProvider gatewayMediaProvider;
    @Inject
    WebContext webContext;

    public Response generate(Request request) {
        return generate(body(request), currentOwner(), false);
    }

    Response generate(byte[] body, MediaJobOwner owner, boolean edit) {
        return json(gatewayMediaProvider.generateImage(imageRequest(body, edit), owner));
    }

    public Response edit(Request request) {
        var imageRequest = request.files().isEmpty() ? imageRequest(body(request), true) : multipartImageRequest(request);
        return json(gatewayMediaProvider.generateImage(imageRequest, currentOwner()));
    }

    ImageGenerationRequest imageRequest(byte[] body, boolean edit) {
        Map<String, Object> value;
        try {
            value = GatewayJson.MAPPER.readValue(body, GatewaySupport.MAP_TYPE);
        } catch (Exception e) {
            throw new BadRequestException("invalid JSON body: " + e.getMessage(), "BAD_REQUEST", e);
        }
        var model = string(value, "model");
        var prompt = string(value, "prompt");
        if (!GatewaySupport.hasText(model)) throw new BadRequestException("image model is required");
        if (!GatewaySupport.hasText(prompt)) throw new BadRequestException("image prompt is required");

        var inputImages = references(first(value, "input_images", "inputImages", "image"), "input_images");
        var mask = reference(first(value, "mask"), "mask");
        if (edit && mask == null && (inputImages == null || inputImages.isEmpty())) {
            throw new BadRequestException("image edits require input_images or mask");
        }
        return new ImageGenerationRequest(
                model, prompt, integer(value.get("n")), string(value, "size"), string(value, "quality"),
                string(value, "output_format", "outputFormat"),
                integer(first(value, "output_compression", "outputCompression")), string(value, "background"),
                inputImages, mask, jsonString(first(value, "provider_extra", "providerExtra")),
                string(value, "previous_interaction_id", "previousInteractionId"));
    }

    ImageGenerationRequest multipartImageRequest(Request request) {
        var fields = request.formParams();
        var model = fields.get("model");
        var prompt = fields.get("prompt");
        if (!GatewaySupport.hasText(model)) throw new BadRequestException("image model is required");
        if (!GatewaySupport.hasText(prompt)) throw new BadRequestException("image prompt is required");

        var inputImages = new ArrayList<MediaReference>();
        MediaReference mask = null;
        try {
            for (var entry : request.files().entrySet()) {
                var file = entry.getValue();
                var contentType = GatewaySupport.hasText(file.contentType) ? file.contentType : "application/octet-stream";
                var reference = new MediaReference(null,
                        "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(file.path)));
                if ("mask".equals(entry.getKey())) mask = reference;
                else if (entry.getKey().startsWith("image")) inputImages.add(reference);
            }
        } catch (IOException e) {
            throw new BadRequestException("failed to read uploaded image: " + e.getMessage(), "BAD_REQUEST", e);
        }
        if (mask == null && inputImages.isEmpty()) throw new BadRequestException("image edits require an image or mask file");
        return new ImageGenerationRequest(
                model, prompt, integer(fields.get("n")), fields.get("size"), fields.get("quality"),
                firstText(fields, "output_format", "outputFormat"),
                integer(firstText(fields, "output_compression", "outputCompression")), fields.get("background"),
                inputImages.isEmpty() ? null : inputImages, mask,
                firstText(fields, "provider_extra", "providerExtra"),
                firstText(fields, "previous_interaction_id", "previousInteractionId"));
    }

    private List<MediaReference> references(Object value, String argumentName) {
        if (value == null) return null;
        try {
            if (value instanceof List<?>) return MediaReferenceParser.parse(jsonString(value), argumentName);
            return List.of(MediaReferenceParser.parseItem(value, argumentName));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage(), "BAD_REQUEST", e);
        }
    }

    private MediaReference reference(Object value, String argumentName) {
        if (value == null) return null;
        try {
            return MediaReferenceParser.parseItem(value, argumentName);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage(), "BAD_REQUEST", e);
        }
    }

    private Object first(Map<String, Object> value, String... names) {
        for (var name : names) {
            var result = value.get(name);
            if (result != null) return result;
        }
        return null;
    }

    private String string(Map<String, Object> value, String... names) {
        var result = first(value, names);
        return result instanceof String text ? text : null;
    }

    private Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private Integer integer(String value) {
        if (!GatewaySupport.hasText(value)) return null;
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new BadRequestException("expected an integer but got: " + value, "BAD_REQUEST", e);
        }
    }

    private String firstText(Map<String, String> value, String... names) {
        for (var name : names) {
            var result = value.get(name);
            if (result != null) return result;
        }
        return null;
    }

    private String jsonString(Object value) {
        if (value == null) return null;
        if (value instanceof String text) return text;
        try {
            return GatewayJson.MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("invalid JSON value: " + e.getMessage(), "BAD_REQUEST", e);
        }
    }

    private Response json(ImageGenerationResponse value) {
        var body = new LinkedHashMap<String, Object>();
        body.put("data", images(value.data()));
        body.put("usage", usage(value.usage()));
        body.put("interaction_id", value.interactionId());
        body.put("media_id", value.mediaId());
        if (!value.notes().isEmpty()) body.put("notes", value.notes());
        try {
            return Response.bytes(GatewayJson.MAPPER.writeValueAsBytes(body)).contentType(ContentType.APPLICATION_JSON);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("failed to serialize gateway image response", e);
        }
    }

    private List<Map<String, Object>> images(List<ImageData> values) {
        if (values == null) return List.of();
        var images = new ArrayList<Map<String, Object>>();
        for (var value : values) {
            var image = new LinkedHashMap<String, Object>();
            image.put("b64_json", value.b64Json());
            image.put("url", value.url());
            image.put("revised_prompt", value.revisedPrompt());
            images.add(image);
        }
        return images;
    }

    private Map<String, Object> usage(Usage value) {
        if (value == null) return null;
        var body = new LinkedHashMap<String, Object>();
        body.put("total_tokens", value.totalTokens());
        body.put("image_count", value.imageCount());
        body.put("input_tokens", value.inputTokens());
        body.put("output_tokens", value.outputTokens());
        body.put("input_text_tokens", value.inputTextTokens());
        body.put("input_image_tokens", value.inputImageTokens());
        body.put("upstream_cost_usd", value.upstreamCostUsd());
        return body;
    }

    private MediaJobOwner currentOwner() {
        return new MediaJobOwner(AuthContext.userId(webContext), null, null);
    }

    private byte[] body(Request request) {
        return request.body().orElseThrow(() -> new BadRequestException("body is required"));
    }
}
