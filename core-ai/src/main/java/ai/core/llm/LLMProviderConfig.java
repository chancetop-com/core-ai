package ai.core.llm;

import ai.core.utils.JsonUtil;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * @author stephen
 */
public class LLMProviderConfig {
    private String model;
    private String multiModalModel;
    private Double temperature;
    private String embeddingModel;
    private Object requestExtraBody;
    private final Map<String, Object> modelExtraBodies = new HashMap<>();
    private Duration timeout = Duration.ofSeconds(300);
    private Duration connectTimeout = Duration.ofSeconds(3);
    // how long a client keeps retrying while the core-ai-server CLI proxy is unreachable
    // (a rolling restart can take tens of seconds); only applies to the server proxy endpoint
    private Duration serverRetryDuration = Duration.ofSeconds(90);
    private int streamBufferSize = 0;
    private int maxInlineImages = InlineImagePruner.DEFAULT_MAX_IMAGES;
    private long maxInlineImageBytes = InlineImagePruner.DEFAULT_MAX_BYTES;

    public LLMProviderConfig(String model, Double temperature, String embeddingModel) {
        this.model = model;
        this.temperature = temperature;
        this.embeddingModel = embeddingModel;
    }

    public LLMProviderConfig(LLMProviderConfig other) {
        this.model = other.model;
        this.multiModalModel = other.multiModalModel;
        this.temperature = other.temperature;
        this.embeddingModel = other.embeddingModel;
        this.requestExtraBody = other.requestExtraBody;
        this.modelExtraBodies.putAll(other.modelExtraBodies);
        this.timeout = other.timeout;
        this.connectTimeout = other.connectTimeout;
        this.serverRetryDuration = other.serverRetryDuration;
        this.streamBufferSize = other.streamBufferSize;
        this.maxInlineImages = other.maxInlineImages;
        this.maxInlineImageBytes = other.maxInlineImageBytes;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Long connectTimeout) {
        this.connectTimeout = Duration.ofSeconds(connectTimeout);
    }

    public void setTimeout(Long timeout) {
        this.timeout = Duration.ofSeconds(timeout);
    }

    public Duration getServerRetryDuration() {
        return serverRetryDuration;
    }

    public void setServerRetrySeconds(Long seconds) {
        this.serverRetryDuration = Duration.ofSeconds(seconds);
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getModel() {
        return model;
    }

    public String getMultiModalModel() {
        return multiModalModel;
    }

    public void setMultiModalModel(String multiModalModel) {
        this.multiModalModel = multiModalModel;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public Object getRequestExtraBody() {
        return requestExtraBody;
    }

    public void setRequestExtraBody(String requestExtraBody) {
        this.requestExtraBody = JsonUtil.fromJson(Object.class, requestExtraBody);
    }

    public void addModelExtraBody(String modelName, String json) {
        if (json == null || json.isBlank()) {
            modelExtraBodies.put(modelName, null);
        } else {
            modelExtraBodies.put(modelName, JsonUtil.fromJson(Object.class, json));
        }
    }

    public Object resolveExtraBody(String modelName) {
        if (modelName == null) {
            return requestExtraBody;
        }
        var body = modelExtraBodies.get(modelName);
        if (body != null) {
            return body;
        }
        var slash = modelName.lastIndexOf('/');
        if (slash < 0) {
            return requestExtraBody;
        }
        var nameOnly = modelName.substring(slash + 1);
        var nameBody = modelExtraBodies.get(nameOnly);
        return nameBody != null ? nameBody : requestExtraBody;
    }

    /**
     * Drops {@code reasoning_effort} from the inherited extra bodies. Direct providers keep their own
     * effort under {@code <type>.request.extra_body}; leaking the gateway-wide value into them sends
     * levels the vendor rejects (e.g. DeepSeek sees {@code max}).
     */
    public void removeReasoningEffort() {
        requestExtraBody = removeReasoningEffort(requestExtraBody);
        modelExtraBodies.replaceAll((model, body) -> removeReasoningEffort(body));
    }

    /** Rewrites an existing {@code reasoning_effort} value through a vendor-specific mapper. */
    public void mapReasoningEffort(UnaryOperator<String> mapper) {
        requestExtraBody = mapReasoningEffort(requestExtraBody, mapper);
        modelExtraBodies.replaceAll((model, body) -> mapReasoningEffort(body, mapper));
    }

    private static Object removeReasoningEffort(Object value) {
        if (!(value instanceof Map<?, ?> map) || !map.containsKey("reasoning_effort")) return value;
        var copy = new LinkedHashMap<String, Object>();
        map.forEach((key, item) -> {
            if (!"reasoning_effort".equals(String.valueOf(key))) copy.put(String.valueOf(key), item);
        });
        return copy;
    }

    private static Object mapReasoningEffort(Object value, UnaryOperator<String> mapper) {
        if (!(value instanceof Map<?, ?> map) || !(map.get("reasoning_effort") instanceof String effort)) return value;
        var copy = new LinkedHashMap<String, Object>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        copy.put("reasoning_effort", mapper.apply(effort));
        return copy;
    }

    public int getStreamBufferSize() {
        return streamBufferSize;
    }

    public void setStreamBufferSize(int streamBufferSize) {
        this.streamBufferSize = streamBufferSize;
    }

    public int getMaxInlineImages() {
        return maxInlineImages;
    }

    public void setMaxInlineImages(int maxInlineImages) {
        this.maxInlineImages = maxInlineImages;
    }

    public long getMaxInlineImageBytes() {
        return maxInlineImageBytes;
    }

    public void setMaxInlineImageBytes(long maxInlineImageBytes) {
        this.maxInlineImageBytes = maxInlineImageBytes;
    }
}
