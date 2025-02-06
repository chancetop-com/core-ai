package ai.core.llm;

/**
 * @author stephen
 */
public class LLMProviderConfig {
    private String model;
    private Double temperature;

    public LLMProviderConfig(String model, Double temperature) {
        this.model = model;
        this.temperature = temperature;
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
}
