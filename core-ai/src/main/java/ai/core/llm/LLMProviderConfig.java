package ai.core.llm;

import java.time.Duration;

/**
 * @author stephen
 */
public class LLMProviderConfig {
    private String model;
    private Double temperature;
    private String embeddingModel;
    private Duration timeout = Duration.ofSeconds(60);
    private Duration connectTimeout = Duration.ofSeconds(3);

    public LLMProviderConfig(String model, Double temperature, String embeddingModel) {
        this.model = model;
        this.temperature = temperature;
        this.embeddingModel = embeddingModel;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
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
}
