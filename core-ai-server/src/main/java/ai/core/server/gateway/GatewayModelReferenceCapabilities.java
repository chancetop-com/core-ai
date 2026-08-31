package ai.core.server.gateway;

import ai.core.api.server.gateway.GatewayModelRequest;
import ai.core.api.server.gateway.GatewayModelView;
import ai.core.media.reference.MediaAddressingSyntax;
import ai.core.server.domain.GatewayModelConfig;
import core.framework.web.exception.BadRequestException;

import java.util.function.BiPredicate;

/**
 * Admin-editable per-model reference and video-render facts. Every field is nullable and a null keeps the code-level
 * model-family default, so registering a new media model stays one row rather than a code change.
 *
 * @author Stephen
 */
final class GatewayModelReferenceCapabilities {
    static void apply(GatewayModelConfig entity, GatewayModelRequest request, BiPredicate<String, Boolean> specified, boolean create) {
        if (specified.test("maxImageReferences", create)) entity.maxImageReferences = request.maxImageReferences;
        if (specified.test("maxVideoReferences", create)) entity.maxVideoReferences = request.maxVideoReferences;
        if (specified.test("maxAudioReferences", create)) entity.maxAudioReferences = request.maxAudioReferences;
        if (specified.test("maxMixedReferences", create)) entity.maxMixedReferences = request.maxMixedReferences;
        if (specified.test("addressingSyntax", create)) entity.addressingSyntax = normalizeAddressingSyntax(request.addressingSyntax);
        if (specified.test("acceptsAudioReference", create)) entity.acceptsAudioReference = request.acceptsAudioReference;
        if (specified.test("nativeAudio", create)) entity.nativeAudio = request.nativeAudio;
        if (specified.test("maxOutputDurationSec", create)) entity.maxOutputDurationSec = request.maxOutputDurationSec;
    }

    static void view(GatewayModelView view, GatewayModelConfig entity) {
        view.maxImageReferences = entity.maxImageReferences;
        view.maxVideoReferences = entity.maxVideoReferences;
        view.maxAudioReferences = entity.maxAudioReferences;
        view.maxMixedReferences = entity.maxMixedReferences;
        view.addressingSyntax = entity.addressingSyntax;
        view.acceptsAudioReference = entity.acceptsAudioReference;
        view.nativeAudio = entity.nativeAudio;
        view.maxOutputDurationSec = entity.maxOutputDurationSec;
    }

    static void validate(GatewayModelRequest request) {
        rejectNegative("maxImageReferences", request.maxImageReferences);
        rejectNegative("maxVideoReferences", request.maxVideoReferences);
        rejectNegative("maxAudioReferences", request.maxAudioReferences);
        rejectNegative("maxMixedReferences", request.maxMixedReferences);
        normalizeAddressingSyntax(request.addressingSyntax);
        if (request.maxOutputDurationSec != null && request.maxOutputDurationSec <= 0)
            throw new BadRequestException("maxOutputDurationSec must be positive");
    }

    private static void rejectNegative(String field, Integer value) {
        if (value != null && value < 0) throw new BadRequestException(field + " must not be negative");
    }

    /** An empty value clears the override and falls back to the code-level model-family default. */
    private static String normalizeAddressingSyntax(String value) {
        if (value == null || value.isBlank()) return null;
        var syntax = MediaAddressingSyntax.parse(value);
        if (syntax == null) throw new BadRequestException("addressingSyntax must be one of AT_TOKEN, BRACKET, ANGLE_SUBJECT, NONE");
        return syntax.name();
    }

    private GatewayModelReferenceCapabilities() {
    }
}
