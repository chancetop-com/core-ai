package ai.core.llm;

import ai.core.utils.JsonUtil;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

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
    private int streamBufferSize = 0;

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
        this.streamBufferSize = other.streamBufferSize;
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

    public int getStreamBufferSize() {
        return streamBufferSize;
    }

    public void setStreamBufferSize(int streamBufferSize) {
        this.streamBufferSize = streamBufferSize;
    }
}
