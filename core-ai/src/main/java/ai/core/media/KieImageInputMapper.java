package ai.core.media;

import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.MediaReference;
import ai.core.utils.JsonUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds the {@code input} object of a KIE image task: the reference array, the aspect ratio, and the
 * family-specific quality/size contract (Seedream's quality enum, Seedream 5.5's size tier, MiniMax
 * H3's resolution tier). Kept apart from {@link KieMediaProvider} because these are model-page
 * contracts, not transport concerns.
 *
 * @author Stephen
 */
final class KieImageInputMapper {
    // KIE image models take a fixed aspect_ratio enum instead of a pixel size; the widest ratio first
    // only matters for readability, the nearest match is picked by log distance
    private static final List<String> IMAGE_ASPECT_RATIOS = List.of("21:9", "16:9", "3:2", "4:3", "1:1", "3:4", "2:3", "9:16");
    // MiniMax H3 image editing adds 5:4/4:5 and defaults to the first reference's ratio
    private static final List<String> MINIMAX_H3_IMAGE_ASPECT_RATIOS = List.of("21:9", "16:9", "3:2", "5:4", "4:3", "1:1",
            "4:5", "3:4", "2:3", "9:16");
    // seedream/nano-banana/flux image editing on KIE all take the references as an image_urls array
    private static final String IMAGE_REFERENCE_FIELD = "image_urls";
    private static final int MAX_IMAGE_REFERENCES = 10;
    private static final int MAX_MINIMAX_H3_IMAGE_REFERENCES = 4;
    private static final String DEFAULT_IMAGE_ASPECT_RATIO = "1:1";
    private static final String DEFAULT_IMAGE_QUALITY = "basic";
    // Seedream 5.5 dropped the quality enum for a resolution tier
    private static final String SEEDREAM_55_PREFIX = "seedream/5-5-";
    private static final String MINIMAX_H3_IMAGE_EDIT_PREFIX = "minimax-h3/image-edit";

    private final Map<String, Object> defaults;
    private final Function<List<MediaReference>, List<String>> referenceUrls;

    KieImageInputMapper(Map<String, Object> defaults, Function<List<MediaReference>, List<String>> referenceUrls) {
        this.defaults = defaults;
        this.referenceUrls = referenceUrls;
    }

    Map<String, Object> input(ImageGenerationRequest request) {
        var input = new LinkedHashMap<>(defaults);
        input.put("prompt", request.prompt());
        var minimaxEdit = request.model().startsWith(MINIMAX_H3_IMAGE_EDIT_PREFIX);
        if (request.inputImages() != null && !request.inputImages().isEmpty()) {
            if (request.model().contains("text-to-image"))
                throw new IllegalArgumentException(request.model() + " does not accept input images, use the image-to-image model instead");
            var urls = referenceUrls.apply(request.inputImages());
            var maxReferences = minimaxEdit ? MAX_MINIMAX_H3_IMAGE_REFERENCES : MAX_IMAGE_REFERENCES;
            if (urls.size() > maxReferences)
                throw new IllegalArgumentException(request.model() + " accepts at most " + maxReferences + " input images, got " + urls.size());
            input.put(IMAGE_REFERENCE_FIELD, urls);
        } else if (minimaxEdit) {
            throw new IllegalArgumentException(request.model() + " requires at least one input image (1-4)");
        }
        // a custom frame is passed through provider_extra and must not be combined with the derived size parameters
        var customSize = minimaxEdit && hasCustomImageSize(request.providerExtra());
        if (!customSize) {
            var aspectRatio = imageAspectRatio(request.size(), minimaxEdit ? MINIMAX_H3_IMAGE_ASPECT_RATIOS : IMAGE_ASPECT_RATIOS);
            if (aspectRatio != null) input.put("aspect_ratio", aspectRatio);
        }
        if (request.model().startsWith(SEEDREAM_55_PREFIX)) {
            var sizeTier = imageSizeTier(request.quality());
            if (sizeTier != null) input.put("size", sizeTier);
            if (request.background() != null && !request.background().isBlank())
                input.put("background", request.background().trim().toLowerCase(Locale.ROOT));
        } else if (minimaxEdit) {
            var resolution = imageResolutionTier(request.quality());
            if (resolution != null && !customSize) input.put("resolution", resolution);
        } else {
            var quality = imageQuality(request.quality());
            if (quality != null) input.put("quality", quality);
            // KIE marks both as required; fall back only when neither the request nor the provider defaults set them
            input.putIfAbsent("quality", DEFAULT_IMAGE_QUALITY);
        }
        if (request.outputFormat() != null && !request.outputFormat().isBlank())
            input.put("output_format", request.outputFormat().trim().toLowerCase(Locale.ROOT));
        if (!customSize) input.putIfAbsent("aspect_ratio", minimaxEdit ? "auto" : DEFAULT_IMAGE_ASPECT_RATIO);
        return input;
    }

    /** Whether provider_extra carries a complete custom frame (width + height), which replaces resolution/aspect_ratio. */
    @SuppressWarnings("unchecked")
    private boolean hasCustomImageSize(String providerExtra) {
        if (providerExtra == null || providerExtra.isBlank()) return false;
        var extra = (Map<String, Object>) JsonUtil.fromJson(Map.class, providerExtra);
        var nested = extra.get("input");
        var input = nested instanceof Map<?, ?> map ? (Map<String, Object>) map : extra;
        return input.get("width") instanceof Number && input.get("height") instanceof Number;
    }

    /**
     * KIE image models accept an aspect_ratio enum, not a pixel size. Callers speak the OpenAI
     * "1024x1536" dialect, so map to the nearest supported ratio; an aspect ratio passed through
     * verbatim is accepted as-is.
     */
    private String imageAspectRatio(String size, List<String> candidates) {
        var ratio = ratio(size);
        if (ratio == null) return null;
        String nearest = null;
        var nearestDistance = Double.MAX_VALUE;
        for (var candidate : candidates) {
            var candidateRatio = ratio(candidate);
            var distance = Math.abs(Math.log(ratio) - Math.log(candidateRatio));
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = candidate;
            }
        }
        return nearest;
    }

    // accepts both the OpenAI "1024x1536" pixel size and a bare "3:2" aspect ratio
    private Double ratio(String value) {
        if (value == null || value.isBlank()) return null;
        var dimensions = value.trim().toLowerCase(Locale.ROOT).split(value.indexOf(':') >= 0 ? ":" : "x");
        if (dimensions.length != 2) return null;
        try {
            var width = Double.parseDouble(dimensions[0].trim());
            var height = Double.parseDouble(dimensions[1].trim());
            return width <= 0 || height <= 0 ? null : width / height;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * KIE grades images as basic/high (Seedream 5.0 Pro) or basic/high/ultra (Seedream 5.0 Lite).
     * The OpenAI-style low/medium/high/auto values are folded onto that scale; "ultra" is only
     * reachable by asking for it explicitly so a plain "high" never silently upgrades the tier.
     */
    private String imageQuality(String quality) {
        if (quality == null || quality.isBlank()) return null;
        return switch (quality.trim().toLowerCase(Locale.ROOT)) {
            case "basic", "low", "medium" -> "basic";
            case "high" -> "high";
            case "ultra" -> "ultra";
            case "auto" -> null;
            default -> throw new IllegalArgumentException("unsupported image quality: " + quality + ", expected basic/high/ultra");
        };
    }

    /**
     * Seedream 5.5 takes a resolution tier instead of the quality enum. The OpenAI-style values
     * callers speak are folded onto that scale; "auto" (and an omitted value) leaves the choice
     * to the model, which defaults to 2K.
     */
    private String imageSizeTier(String quality) {
        if (quality == null || quality.isBlank()) return null;
        return switch (quality.trim().toLowerCase(Locale.ROOT)) {
            case "1k", "basic", "low" -> "1K";
            case "1.5k", "medium" -> "1.5K";
            case "2k", "high", "ultra" -> "2K";
            case "auto" -> null;
            default -> throw new IllegalArgumentException("unsupported image quality: " + quality + ", expected 1K/1.5K/2K or low/medium/high/auto");
        };
    }

    /**
     * MiniMax H3 image editing takes a 1K/2K/4K resolution tier and no quality enum. OpenAI-style values
     * fold onto that scale; "auto" (and an omitted value) leaves the choice to the model, which defaults to 2K.
     */
    private String imageResolutionTier(String quality) {
        if (quality == null || quality.isBlank()) return null;
        return switch (quality.trim().toLowerCase(Locale.ROOT)) {
            case "1k", "basic", "low" -> "1K";
            case "2k", "medium", "high" -> "2K";
            case "4k", "ultra", "xhigh", "max" -> "4K";
            case "auto" -> null;
            default -> throw new IllegalArgumentException("unsupported image quality: " + quality + ", expected 1K/2K/4K or low/medium/high/auto");
        };
    }
}
