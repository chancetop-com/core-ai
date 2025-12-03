package ai.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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

                modelInfoMap.put(modelName, new ModelInfo(maxInputTokens, maxOutputTokens, provider, mode));
            }

            LOGGER.info("Loaded {} model entries from context registry", modelInfoMap.size());
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

    private String getTextOrNull(JsonNode node, String field) {
        var fieldNode = node.get(field);
        if (fieldNode != null && fieldNode.isTextual()) {
            return fieldNode.asText();
        }
        return null;
    }

    public ModelInfo getModelInfo(String modelName) {
        return modelInfoMap.get(modelName);
    }

    public int getMaxInputTokens(String modelName) {
        var info = findModelInfo(modelName);
        return info != null ? info.maxInputTokens() : DEFAULT_MAX_INPUT_TOKENS;
    }

    public int getMaxOutputTokens(String modelName) {
        var info = findModelInfo(modelName);
        return info != null ? info.maxOutputTokens() : DEFAULT_MAX_OUTPUT_TOKENS;
    }

    public int getContextWindow(String modelName) {
        return getMaxInputTokens(modelName);
    }

    public ModelInfo findModelInfo(String modelName) {
        if (modelName == null) {
            return null;
        }

        // Try exact match first
        var info = modelInfoMap.get(modelName);
        if (info != null) {
            return info;
        }

        // Try with common prefixes for Azure/other providers
        String[] prefixes = {"azure/", "openai/", "anthropic/", "bedrock/"};
        for (var prefix : prefixes) {
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
        return findModelInfo(modelName) != null;
    }

    public int size() {
        return modelInfoMap.size();
    }

    public record ModelInfo(
            int maxInputTokens,
            int maxOutputTokens,
            String provider,
            String mode) {
        public int contextWindow() {
            return maxInputTokens;
        }
    }
}
