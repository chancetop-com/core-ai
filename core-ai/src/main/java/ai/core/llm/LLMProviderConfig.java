package ai.core.llm;

import ai.core.utils.JsonUtil;

import java.time.Duration;

/**
 * @author stephen
 */
public class LLMProviderConfig {
    private String model;
    private Double temperature;
    private String embeddingModel;
    private Object requestExtraBody;
    private Duration timeout = Duration.ofSeconds(60);
    private Duration connectTimeout = Duration.ofSeconds(3);

    public LLMProviderConfig(String model, Double temperature, String embeddingModel) {
        this.model = model;
        this.temperature = temperature;
        this.embeddingModel = embeddingModel;
    }

    public LLMProviderConfig(LLMProviderConfig other) {
        this.model = other.model;
        this.temperature = other.temperature;
        this.embeddingModel = other.embeddingModel;
        this.requestExtraBody = other.requestExtraBody;
        this.timeout = other.timeout;
        this.connectTimeout = other.connectTimeout;
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
}
