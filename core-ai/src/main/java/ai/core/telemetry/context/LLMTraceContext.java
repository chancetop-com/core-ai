package ai.core.telemetry.context;

/**
 * Context object carrying LLM-specific trace information
 * Avoids circular dependencies between tracers and domain entities
 *
 * @author stephen
 */
public final class LLMTraceContext {
    public static Builder builder() {
        return new Builder();
    }

    private final String providerName;
    private final String model;
    private final Double temperature;
    private int promptTokens;
    private int completionTokens;
    private String finishReason;

    private LLMTraceContext(Builder builder) {
        this.providerName = builder.providerName;
        this.model = builder.model;
        this.temperature = builder.temperature;
        this.promptTokens = builder.promptTokens;
        this.completionTokens = builder.completionTokens;
        this.finishReason = builder.finishReason;
    }

    public String getProviderName() {
        return providerName;
    }

    public String getModel() {
        return model;
    }

    public Double getTemperature() {
        return temperature;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(int promptTokens) {
        this.promptTokens = promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(int completionTokens) {
        this.completionTokens = completionTokens;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public void setFinishReason(String finishReason) {
        this.finishReason = finishReason;
    }

    public static class Builder {
        private String providerName;
        private String model;
        private Double temperature;
        private int promptTokens;
        private int completionTokens;
        private String finishReason;

        public Builder providerName(String providerName) {
            this.providerName = providerName;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder promptTokens(int promptTokens) {
            this.promptTokens = promptTokens;
            return this;
        }

        public Builder completionTokens(int completionTokens) {
            this.completionTokens = completionTokens;
            return this;
        }

        public Builder finishReason(String finishReason) {
            this.finishReason = finishReason;
            return this;
        }

        public LLMTraceContext build() {
            return new LLMTraceContext(this);
        }
    }
}
