package ai.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author stephen
 */
public final class LLMModelContextRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(LLMModelContextRegistry.class);
    private static final String RESOURCE_PATH = "/model_prices_and_context_window.json";
    private static final int DEFAULT_MAX_INPUT_TOKENS = 128000;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String[] MODEL_PREFIXES = {"azure/", "openai/", "anthropic/", "bedrock/"};
    private static final String[] STRIP_PREFIXES = {"azure/", "openai/", "anthropic/", "bedrock/", "deepseek/", "gemini/", "vertex_ai/", "openrouter/", "litellm/"};
    private static final String[] MEDIA_PREFIXES = {"gemini/", "vertex_ai/", "azure/", "openai/"};

    private static volatile LLMModelContextRegistry instance;

    public static LLMModelContextRegistry getInstance() {
        if (instance == null) {
            synchronized (LLMModelContextRegistry.class) {
                if (instance == null) {
                    instance = new LLMModelContextRegistry();
                }
            }
        }
        return instance;
    }

    // Peak hours follow the DeepSeek 2026-08 peak/off-peak scheme: Beijing time 9:00-12:00 and 14:00-18:00.
    public static boolean isPeakHour(Instant when) {
        var time = when.atZone(SHANGHAI);
        var hour = time.getHour();
        return hour >= 9 && hour < 12 || hour >= 14 && hour < 18;
    }

    private final Map<String, ModelInfo> modelInfoMap = new ConcurrentHashMap<>();

    private LLMModelContextRegistry() {
        loadModelInfo();
    }

    private void loadModelInfo() {
        try (var is = getClass().getResourceAsStream(RESOURCE_PATH)) {
            if (is == null) {
                LOGGER.warn("Model context registry resource not found: {}", RESOURCE_PATH);
                return;
            }

            var mapper = new ObjectMapper();
            var root = mapper.readTree(is);

            var fieldNames = root.fieldNames();
            while (fieldNames.hasNext()) {
                var modelName = fieldNames.next();
                var modelNode = root.get(modelName);

                // Skip sample_spec
                if ("sample_spec".equals(modelName)) {
                    continue;
                }

                var maxInputTokens = getIntOrDefault(modelNode, "max_input_tokens", getIntOrDefault(modelNode, "max_tokens", DEFAULT_MAX_INPUT_TOKENS));
                var maxOutputTokens = getIntOrDefault(modelNode, "max_output_tokens", getIntOrDefault(modelNode, "max_tokens", DEFAULT_MAX_OUTPUT_TOKENS));
                var provider = getTextOrNull(modelNode, "litellm_provider");
                var mode = getTextOrNull(modelNode, "mode");
                var inputCostPerToken = getDoubleOrDefault(modelNode, "input_cost_per_token", 0.0);
                var outputCostPerToken = getDoubleOrDefault(modelNode, "output_cost_per_token", 0.0);
                var cacheReadInputTokenCost = getDoubleOrDefault(modelNode, "cache_read_input_token_cost",
                    getDoubleOrDefault(modelNode, "input_cost_per_token_cache_hit", inputCostPerToken));
                // Optional peak-hour price multiplier (e.g. DeepSeek 2026-08 peak/off-peak pricing); 1.0 = no peak pricing.
                var peakPriceMultiplier = getDoubleOrDefault(modelNode, "peak_price_multiplier", 1.0);
                var supportsVision = getBooleanOrNull(modelNode, "supports_vision");
                var supportsPdfInput = getBooleanOrNull(modelNode, "supports_pdf_input");
                var supportsVideoInput = getBooleanOrNull(modelNode, "supports_video_input");
                var mediaMode = "video_generation".equals(mode) || "image_generation".equals(mode);
                var outputCostPerImage = getDoubleOrNull(modelNode, "output_cost_per_image");
                // bedrock chat models carry a compute output_cost_per_second that is not media pricing — only read for media modes
                var outputCostPerSecond = mediaMode ? getDoubleOrNull(modelNode, "output_cost_per_second") : null;
                var inputCostPerImageToken = getDoubleOrNull(modelNode, "input_cost_per_image_token");
                var outputCostPerImageToken = getDoubleOrNull(modelNode, "output_cost_per_image_token");

                modelInfoMap.put(modelName, new ModelInfo(maxInputTokens, maxOutputTokens, provider, mode,
                    inputCostPerToken, outputCostPerToken, cacheReadInputTokenCost, peakPriceMultiplier,
                    supportsVision, supportsPdfInput, supportsVideoInput,
                    outputCostPerImage, outputCostPerSecond, inputCostPerImageToken, outputCostPerImageToken));
            }

            LOGGER.debug("Loaded {} model entries from context registry", modelInfoMap.size());
        } catch (IOException e) {
            LOGGER.error("Failed to load model context registry", e);
        }
    }

    private int getIntOrDefault(JsonNode node, String field, int defaultValue) {
        var fieldNode = node.get(field);
        if (fieldNode != null && fieldNode.isNumber()) {
            return fieldNode.asInt();
        }
        return defaultValue;
    }

    private double getDoubleOrDefault(JsonNode node, String field, double defaultValue) {
        var fieldNode = node.get(field);
        if (fieldNode != null && fieldNode.isNumber()) {
            return fieldNode.asDouble();
        }
        return defaultValue;
    }

    private Double getDoubleOrNull(JsonNode node, String field) {
        var fieldNode = node.get(field);
        return fieldNode != null && fieldNode.isNumber() ? fieldNode.asDouble() : null;
    }

    private Boolean getBooleanOrNull(JsonNode node, String field) {
        var fieldNode = node.get(field);
        if (fieldNode != null && fieldNode.isBoolean()) {
            return fieldNode.asBoolean();
        }
        return null;
    }

    private String getTextOrNull(JsonNode node, String field) {
        var fieldNode = node.get(field);
        if (fieldNode != null && fieldNode.isTextual()) {
            return fieldNode.asText();
        }
        return null;
    }

    public int getMaxInputTokens(String modelName) {
        var info = getModelInfo(modelName);
        return info != null ? info.maxInputTokens() : DEFAULT_MAX_INPUT_TOKENS;
    }

    public ModelInfo getModelInfo(String modelName) {
        if (modelName == null) return null;
        var info = modelInfoMap.get(modelName);
        if (info != null) {
            return info;
        }

        // routing-convention names (e.g. azure/responses/gpt-5-mini) are not litellm keys; drop the marker segment
        if (modelName.contains("responses/")) {
            info = getModelInfo(modelName.replaceFirst("responses/", ""));
            if (info != null) {
                return info;
            }
        }

        // Try stripping a known provider prefix (litellm also keys many models without it)
        for (var prefix : STRIP_PREFIXES) {
            if (modelName.startsWith(prefix)) {
                info = modelInfoMap.get(modelName.substring(prefix.length()));
                if (info != null) {
                    return info;
                }
                break;
            }
        }

        // Try with common prefixes for Azure/other providers
        for (var prefix : MODEL_PREFIXES) {
            info = modelInfoMap.get(prefix + modelName);
            if (info != null) {
                return info;
            }
        }

        // Try to find a matching base model (e.g., gpt-4o-2024-05-13 -> gpt-4o)
        var baseName = extractBaseModelName(modelName);
        if (!baseName.equals(modelName)) {
            info = modelInfoMap.get(baseName);
            return info;
        }

        return null;
    }

    private String extractBaseModelName(String modelName) {
        // Remove date-based version suffixes (e.g., -2024-05-13)
        var result = modelName.replaceAll("-\\d{4}-\\d{2}-\\d{2}$", "");

        // Remove preview/beta suffixes
        result = result.replaceAll("-preview$", "");
        result = result.replaceAll("-beta$", "");

        return result;
    }

    public boolean hasModel(String modelName) {
        return getModelInfo(modelName) != null;
    }

    public Double estimateCostUsd(String modelName, long inputTokens, long outputTokens, long cachedInputTokens) {
        return estimateCostUsd(modelName, inputTokens, outputTokens, cachedInputTokens, Instant.now());
    }

    public Double estimateCostUsd(String modelName, long inputTokens, long outputTokens, long cachedInputTokens, Instant when) {
        var info = getModelInfo(modelName);
        if (info == null) return null;

        var safeInputTokens = Math.max(inputTokens, 0);
        var safeOutputTokens = Math.max(outputTokens, 0);
        var safeCachedTokens = Math.min(Math.max(cachedInputTokens, 0), safeInputTokens);
        var uncachedInputTokens = safeInputTokens - safeCachedTokens;
        var multiplier = info.peakPriceMultiplier() > 0 && isPeakHour(when) ? info.peakPriceMultiplier() : 1.0;

        return (uncachedInputTokens * info.inputCostPerToken()
            + safeCachedTokens * info.cacheReadInputTokenCost()
            + safeOutputTokens * info.outputCostPerToken()) * multiplier;
    }

    public int size() {
        return modelInfoMap.size();
    }

    /**
     * Image generation estimate. Token detail path first (gpt-image style):
     *   textInput x input_cost_per_token + imageInput x input_cost_per_image_token + output x output_cost_per_image_token.
     * Falls back to per-image pricing when token details or token prices are unavailable:
     *   imageCount x output_cost_per_image. Null when neither path can price the request.
     */
    public MediaCostEstimate estimateImageCost(String modelName, Integer inputTextTokens, Integer inputImageTokens,
                                               Integer outputTokens, Integer imageCount) {
        var entry = findMediaEntry(modelName);
        if (entry == null) return null;
        var info = entry.info();
        if (info.inputCostPerImageToken() != null && info.outputCostPerImageToken() != null
                && (inputTextTokens != null || inputImageTokens != null || outputTokens != null)) {
            var textIn = safeInt(inputTextTokens);
            var imageIn = safeInt(inputImageTokens);
            var output = safeInt(outputTokens);
            var cost = textIn * info.inputCostPerToken()
                    + imageIn * info.inputCostPerImageToken()
                    + output * info.outputCostPerImageToken();
            return new MediaCostEstimate(cost, entry.key(), outputTokens == null ? null : (double) outputTokens, "token");
        }
        if (imageCount != null && imageCount > 0 && info.outputCostPerImage() != null) {
            return new MediaCostEstimate(info.outputCostPerImage() * imageCount, entry.key(), (double) imageCount, "image");
        }
        return null;
    }

    public Double estimateImageCostUsd(String modelName, Integer inputTextTokens, Integer inputImageTokens,
                                       Integer outputTokens, Integer imageCount) {
        var estimate = estimateImageCost(modelName, inputTextTokens, inputImageTokens, outputTokens, imageCount);
        return estimate == null ? null : estimate.costUsd();
    }

    /** Video generation estimate: seconds x output_cost_per_second; null when the model or price is unavailable. */
    public MediaCostEstimate estimateVideoCost(String modelName, Integer seconds) {
        if (seconds == null || seconds <= 0) return null;
        var entry = findMediaEntry(modelName);
        if (entry == null || entry.info().outputCostPerSecond() == null) return null;
        return new MediaCostEstimate(entry.info().outputCostPerSecond() * seconds, entry.key(), (double) seconds, "second");
    }

    public Double estimateVideoCostUsd(String modelName, Integer seconds) {
        var estimate = estimateVideoCost(modelName, seconds);
        return estimate == null ? null : estimate.costUsd();
    }

    // Media catalogs are keyed with provider prefixes (gemini/veo-3.1-generate-001, azure/gpt-image-2) while
    // upstream model names are usually bare (veo-3.1-generate-001, gpt-image-2).
    private MediaEntry findMediaEntry(String modelName) {
        if (modelName == null || modelName.isBlank()) return null;
        var info = modelInfoMap.get(modelName);
        if (info != null) return new MediaEntry(modelName, info);
        for (var prefix : MEDIA_PREFIXES) {
            if (modelName.startsWith(prefix)) {
                var bare = modelName.substring(prefix.length());
                info = modelInfoMap.get(bare);
                if (info != null) return new MediaEntry(bare, info);
                break;
            }
        }
        for (var prefix : MEDIA_PREFIXES) {
            var prefixed = prefix + modelName;
            info = modelInfoMap.get(prefixed);
            if (info != null) return new MediaEntry(prefixed, info);
        }
        info = getModelInfo(modelName);
        return info == null ? null : new MediaEntry(modelName, info);
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : Math.max(value, 0);
    }

    public record ModelInfo(
            int maxInputTokens,
            int maxOutputTokens,
            String provider,
            String mode,
            double inputCostPerToken,
            double outputCostPerToken,
            double cacheReadInputTokenCost,
            double peakPriceMultiplier,
            Boolean supportsVision,
            Boolean supportsPdfInput,
            Boolean supportsVideoInput,
            Double outputCostPerImage,
            Double outputCostPerSecond,
            Double inputCostPerImageToken,
            Double outputCostPerImageToken) {
        public int contextWindow() {
            return maxInputTokens;
        }
    }

    public record MediaCostEstimate(Double costUsd, String pricingModelId, Double units, String unitType) {
    }

    private record MediaEntry(String key, ModelInfo info) {
    }
}
