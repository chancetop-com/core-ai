package ai.core.llm;

/**
 * @author stephen
 */
public class LLMProviderConfig {
    private String model;
    private Double temperature;
    private String embeddingModel;

    public LLMProviderConfig(String model, Double temperature, String embeddingModel) {
        this.model = model;
        this.temperature = temperature;
        this.embeddingModel = embeddingModel;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getModel() {
        return model;
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
